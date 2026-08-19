# Organization-based access to data

## Context

`prompts.md` currently just states the goal: "implementing Organization based
access to the system/data." Exploration of the codebase shows this is
**partially built already**, inconsistently:

- `User.organizationId` (plain `UUID`, no FK) is populated at login and
  embedded in the JWT (`AuthenticatedUser.organizationId`), per
  `backend-architecture.md` §"Users, Roles & Organizations." For
  `INSURER_USER` it's meant to equal an `Insurer.id`; for `PROVIDER_USER` an
  `ServiceProvider.id`.
- Three service impls duplicate ad-hoc scoping logic by hand:
  - `PolicyServiceImpl` (`findScoped`/`assertVisibleToCurrentUser`, lines
    ~104-123): scopes list + get/update by `insurerId` for `INSURER_USER`
    only.
  - `ClaimServiceImpl` (`findScoped`, line ~242): scopes **list only**, and
    **only** for `PROVIDER_USER` by `serviceProviderId` — `INSURER_USER`
    currently sees every insurer's claims, and `getById` has **no** guard at
    all for either role.
  - `PreauthorizationServiceImpl` (`findScoped`, line ~179): same gaps as
    Claim — `PROVIDER_USER`-only list scoping, no `INSURER_USER` scoping, no
    `getById` guard. Preauthorization also has no `insurerId` column at all
    (only reachable via `policyId → Policy.insurerId`), unlike `Claim` which
    already denormalizes `insurerId` onto itself at creation.
- `User.organizationId` has **zero referential integrity** — it's just a
  UUID that happens to collide with either an `Insurer.id` or a
  `ServiceProvider.id` depending on role, with nothing enforcing that.

The goal of this plan is to (1) close the found gaps so `INSURER_USER` and
`PROVIDER_USER` are consistently and correctly scoped everywhere, (2)
de-duplicate the scoping logic into one reusable mechanism instead of three
hand-rolled copies, and (3) give `organizationId` real integrity by
introducing a first-class `Organization` entity, while touching as little of
the existing, working design as possible.

## Design

**Key simplification:** give `Organization.id` the *same* value as the
`Insurer.id` / `ServiceProvider.id` it represents (shared-PK, not a
generated/independent id). This means:

- `User.organizationId` keeps meaning exactly what it means today (an
  `Insurer.id` or `ServiceProvider.id`) — **no data migration needed on the
  `users` table**, just add an FK constraint once `organizations` rows exist
  for every current `Insurer`/`ServiceProvider`.
- No indirection/lookup is needed at auth time or at query time — existing
  comparisons (`policy.insurerId == user.organizationId`) keep working
  unchanged.
- `Organization` becomes the real place to enforce "does this id refer to a
  legitimate org" and to hold org-level attributes later (status, contact
  info) without touching `Insurer`/`ServiceProvider`.

### 1. New `organization` feature package

- `Organization` entity (extends `BaseEntity`, but **without** `@UuidGenerator`
  on `id` — id is assigned explicitly by the creator, equal to the source
  `Insurer`/`ServiceProvider` id): `name: String`, `type: OrganizationType`.
- `OrganizationType` enum: `INSURER`, `PROVIDER`.
- `OrganizationRepository`, `OrganizationService` (interface) /
  `OrganizationServiceImpl`: `create(UUID id, String name, OrganizationType type)`,
  `existsByIdAndType(UUID id, OrganizationType type)`, `getEntityById(UUID id)`.
  Follows the same interface/impl/mapper/dto shape as `insurer/` and
  `serviceprovider/`.
- `OrganizationController` — read-only listing, `ADMIN` only (mirrors
  `/api/v1/users/**` route rule in `SecurityConfig`), for admin visibility
  into orgs; register `/api/v1/organizations/**` → `hasRole("ADMIN")`.

### 2. Migration — `V<timestamp>__organizations.sql`

(get real timestamp via `date +%Y%m%d%H%M` when writing the file)

```sql
CREATE TABLE organizations (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    type VARCHAR(20) NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_date TIMESTAMP,
    updated_date TIMESTAMP,
    deleted_date TIMESTAMP,
    created_by UUID,
    updated_by UUID
);

INSERT INTO organizations (id, name, type, created_date, created_by, deleted)
SELECT id, name, 'INSURER', created_date, created_by, false FROM insurers;

INSERT INTO organizations (id, name, type, created_date, created_by, deleted)
SELECT id, name, 'PROVIDER', created_date, created_by, false FROM service_providers;

ALTER TABLE users
    ADD CONSTRAINT fk_users_organization FOREIGN KEY (organization_id)
    REFERENCES organizations(id);

-- denormalize insurerId onto preauthorizations, matching the pattern
-- already used on claims, so it can be scoped/queried directly.
ALTER TABLE preauthorizations ADD COLUMN insurer_id UUID;
UPDATE preauthorizations p
    SET insurer_id = pol.insurer_id
    FROM policies pol
    WHERE pol.id = p.policy_id;
ALTER TABLE preauthorizations ALTER COLUMN insurer_id SET NOT NULL;
```

(No FK is added from `insurers`/`service_providers` to `organizations` — that
would be circular given `organizations.id` is sourced *from* those tables;
the relationship is enforced at creation time in application code instead,
matching the existing "plain ID column, no JPA relation" convention.)

### 3. Provision an Organization row on Insurer/ServiceProvider creation

In `InsurerServiceImpl.create()` and `ServiceProviderServiceImpl.create()`,
after saving the entity (so its generated id is known), call
`organizationService.create(insurer.getId(), insurer.getName(), OrganizationType.INSURER)`
(service → service call per `CLAUDE.md`/Layering Rules). Keeps future rows
consistent with the backfilled ones.

### 4. Validate `User.organizationId` against `Organization` on user create/update

In `UserServiceImpl`, when `request.roles()` contains `INSURER_USER` or
`PROVIDER_USER`, require `organizationId` non-null and call
`organizationService.existsByIdAndType(organizationId, matchingType)`,
throwing `IllegalArgumentException` (→ 400, or align with existing validation
exception type used elsewhere in `UserServiceImpl`) if it doesn't match.
`ADMIN`-only users keep `organizationId == null` as today.

### 5. Centralized scoping helper — `common/security/OrgScope.java`

Replaces the three duplicated `findScoped`/`assertVisibleToCurrentUser`
blocks. Pure utility, no feature-package dependency (satisfies "`common/`
must not depend on any feature package"), operating only on
`SecurityUtils.currentUser()` / `AuthenticatedUser`:

```java
public final class OrgScope {
    private OrgScope() {}

    /** true if the current user holds this role (and is therefore restricted to their org's rows). */
    public static boolean restrictsTo(String role) {
        return SecurityUtils.currentUser().map(u -> u.roles().contains(role)).orElse(false);
    }

    /** the id to filter/compare against when restrictsTo(role) is true. */
    public static UUID currentOrganizationId() {
        return SecurityUtils.currentUser()
                .map(AuthenticatedUser::organizationId)
                .orElseThrow(() -> new AccessDeniedException("No authenticated organization"));
    }

    /** throws AccessDeniedException if the current user holds `role` but `resourceOrgId` isn't theirs. */
    public static void assertOwns(UUID resourceOrgId, String role) {
        if (restrictsTo(role) && !currentOrganizationId().equals(resourceOrgId)) {
            throw new AccessDeniedException("Resource belongs to another organization");
        }
    }
}
```

Call sites become uniform, e.g. `ClaimServiceImpl`:

```java
private Page<Claim> findScoped(Pageable pageable) {
    if (OrgScope.restrictsTo("INSURER_USER")) {
        return claimRepository.findAllByInsurerId(OrgScope.currentOrganizationId(), pageable);
    }
    if (OrgScope.restrictsTo("PROVIDER_USER")) {
        return claimRepository.findAllByServiceProviderId(OrgScope.currentOrganizationId(), pageable);
    }
    return claimRepository.findAll(pageable);
}

private void assertVisibleToCurrentUser(Claim claim) {
    OrgScope.assertOwns(claim.getInsurerId(), "INSURER_USER");
    OrgScope.assertOwns(claim.getServiceProviderId(), "PROVIDER_USER");
}
```

...called from `getById` too (currently missing entirely).

### 6. Apply to the three existing call sites, fixing the found gaps

- `PolicyServiceImpl`: replace hand-rolled logic with `OrgScope` calls
  (behavior unchanged — still `INSURER_USER`-only, since policies have no
  service-provider dimension).
- `ClaimServiceImpl`: add `INSURER_USER` list-scoping (currently missing);
  add `assertVisibleToCurrentUser` call inside `getById` (currently missing)
  checking both `insurerId` and `serviceProviderId`.
- `PreauthorizationServiceImpl`: add `insurerId` field to `Preauthorization`
  entity (see migration), set it in `create()` from
  `policyService.getEntityById(request.policyId()).getInsurerId()`; add
  `findAllByInsurerId` to `PreauthorizationRepository`; add both
  `INSURER_USER` and `PROVIDER_USER` list-scoping and a `getById` guard,
  mirroring `ClaimServiceImpl`.

### 7. Documentation

Update `backend-architecture.md` §"Users, Roles & Organizations" (lines
690-709) to describe the new `Organization` entity/table, the shared-PK
relationship to `Insurer`/`ServiceProvider`, and point at `OrgScope` as the
single mechanism for row-level scoping (replacing the current
per-service-impl description). Add a short new subsection or bullet listing
which entities are org-scoped and by which field(s): `Policy.insurerId`,
`Claim.{insurerId, serviceProviderId}`, `Preauthorization.{insurerId, serviceProviderId}`.

## Tests

Per `CLAUDE.md` Definition of Done:

- `OrgScopeTest` (plain unit test, mock `SecurityContextHolder`/
  `AuthenticatedUser`) covering `restrictsTo`, `currentOrganizationId`,
  `assertOwns` allow/deny paths.
- `OrganizationServiceImplTest` — create/existsByIdAndType.
- `ClaimServiceImplTest`: add cases for `INSURER_USER` list-scoping and for
  `getById` throwing `AccessDeniedException` when the claim belongs to a
  different insurer/provider.
- `PreauthorizationServiceImplTest`: same additions, plus insurerId being set
  on create.
- `PolicyServiceImplTest`: adjust existing scoping tests if internals change
  (behavior should stay identical).
- `UserServiceImplTest`: organizationId/type validation on create/update.
- `UserControllerTest` / new `OrganizationControllerTest`
  (`@WebMvcTest`, `@WithMockUser`, `.with(csrf())` on mutating requests per
  project convention).

Run affected modules: `JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn test`.

## Verification

1. `mvn test` — all new and existing tests pass, especially
   `PolicyServiceImplTest`, `ClaimServiceImplTest`,
   `PreauthorizationServiceImplTest`, `UserServiceImplTest`.
2. Manual/API check (or `@WebMvcTest`) confirming:
   - An `INSURER_USER` for Insurer A gets 404/empty list for Insurer B's
     policies/claims/preauthorizations, and 403 (`AccessDeniedException` →
     mapped by `GlobalExceptionHandler`) on direct `getById` of B's records.
   - A `PROVIDER_USER` sees the same behavior scoped by `serviceProviderId`.
   - `ADMIN` is unaffected (sees everything).
3. Confirm Flyway migration applies cleanly against the current dev DB
   (`mvn flyway:migrate` or app boot) and that `organizations` is populated
   1:1 with existing `insurers`/`service_providers` rows.
