# Travel Insurance — Backend Architecture

This document describes the architecture of the Inbound Travel Medical Insurance backend.
It is the reference for how the codebase is organized, how the domain model fits
together, and the conventions every contribution is expected to follow. Read it
before writing your first feature.

The `Policy`, `Benefit`, and `Visitor` shapes described below reflect the
requirements of the Ministry of Health's Mandatory Inbound Travel Health
Insurance framework — see `travel-insurance.md` for the underlying regulatory
summary and the full requirement-vs-codebase gap analysis.

## Contents

- [Travel Insurance — Backend Architecture](#travel-insurance--backend-architecture)
  - [Contents](#contents)
  - [Tech Stack](#tech-stack)
  - [Project Layout (Package by Feature)](#project-layout-package-by-feature)
  - [Core Insurance Flow](#core-insurance-flow)
  - [Users, Roles \& Organizations](#users-roles--organizations)
  - [Layering Rules](#layering-rules)
  - [Base Entity, Auditing \& Soft Delete](#base-entity-auditing--soft-delete)
  - [Conventions](#conventions)
  - [REST Resources](#rest-resources)
  - [Messaging (RabbitMQ)](#messaging-rabbitmq)
  - [Notifications (Policy Document Email)](#notifications-policy-document-email)
  - [API Documentation (Swagger)](#api-documentation-swagger)
  - [Security](#security)
  - [Database Connection Pool (HikariCP)](#database-connection-pool-hikaricp)
  - [Code Practices](#code-practices)
  - [Maven Dependencies](#maven-dependencies)

## Tech Stack

- Java 21
- Spring Boot 3.x (Web, Data JPA, Validation, Security)
- PostgreSQL (runtime) / H2 (tests)
- RabbitMQ (messaging)
- SMTP + Thymeleaf + openhtmltopdf (policy document email on visitor activation)
- Flyway (database migrations)
- Maven

## Project Layout (Package by Feature)

The codebase is organized by **feature**, not by technical layer. Each feature
package contains its own controller, service (interface + implementation),
repository, domain entity, mapper, and DTOs.

```
com.travel.insurance/
│
├── 📁 config/                              # Global configuration
│   ├── SecurityConfig.java                 # Filter chain, route rules, PasswordEncoder
│   ├── JpaAuditingConfig.java              # @EnableJpaAuditing + AuditorAware
│   ├── OpenApiConfig.java                  # Swagger/OpenAPI metadata
│   ├── RabbitConfig.java                   # Exchanges, queues, bindings
│   └── MailProperties.java                 # app.mail.* (from address, emergency-assistance contact)
│
├── 📁 common/                              # Shared, feature-agnostic code
│   ├── 📁 domain/
│   │   └── BaseEntity.java                 # @MappedSuperclass: ID, audit + soft-delete fields
│   ├── 📁 exception/
│   │   ├── GlobalExceptionHandler.java     # @RestControllerAdvice
│   │   ├── ResourceNotFoundException.java
│   │   └── ApiError.java                   # Standard error response body
│   ├── 📁 messaging/
│   │   └── EventPublisher.java             # Thin wrapper over RabbitTemplate
│   ├── 📁 email/
│   │   └── EmailService.java               # Thin wrapper over JavaMailSender
│   └── 📁 util/
│
├── 📁 notification/                        # Feature: Visitor-facing notifications
│   ├── VisitorActivatedNotificationListener.java  # @TransactionalEventListener(AFTER_COMMIT)
│   │                                       # on VisitorStatusChangedEvent; composes
│   │                                       # Visitor+Policy+VisitorBenefit+Insurer data
│   ├── PolicyDocumentRenderer.java         # Thymeleaf → HTML → PDF (openhtmltopdf)
│   └── PolicyDocumentData.java             # Internal template data holder (not a DTO)
│
├── 📁 auth/                                # Feature: Authentication
│   ├── AuthController.java                 # /login, /refresh
│   ├── AuthService.java                    # Service interface
│   ├── AuthServiceImpl.java                # Credential checks, token issuing
│   ├── JwtTokenProvider.java               # Token creation and validation
│   └── 📁 dto/
│       ├── LoginRequest.java
│       └── TokenResponse.java
│
├── 📁 user/                                # Feature: User Management
│   ├── UserController.java                 # Web layer (@RestController)
│   ├── UserService.java                    # Service interface (business contract)
│   ├── UserServiceImpl.java                # @Service implementation
│   ├── UserRepository.java                 # Data access (extends JpaRepository)
│   ├── User.java                           # Domain entity (@Entity)
│   ├── Role.java                           # Enum: ADMIN, INSURER_USER,
│   │                                       #       PROVIDER_USER
│   ├── UserMapper.java                     # Entity ⇄ DTO mapping
│   └── 📁 dto/
│       ├── UserRequest.java
│       └── UserResponse.java
│
├── 📁 insurer/                             # Feature: Insurer Management
│   ├── InsurerController.java
│   ├── InsurerService.java                 # Interface
│   ├── InsurerServiceImpl.java
│   ├── InsurerRepository.java
│   ├── Insurer.java
│   ├── InsurerMapper.java
│   └── 📁 dto/
│       ├── InsurerRequest.java
│       └── InsurerResponse.java
│
├── 📁 serviceprovider/                     # Feature: Service Provider Management
│   ├── ServiceProviderController.java
│   ├── ServiceProviderService.java         # Interface
│   ├── ServiceProviderServiceImpl.java
│   ├── ServiceProviderRepository.java
│   ├── ServiceProvider.java                # name (unique), contactEmail, contactPhone, address
│   ├── ServiceProviderMapper.java
│   └── 📁 dto/
│       ├── ServiceProviderRequest.java
│       └── ServiceProviderResponse.java
│
├── 📁 policy/                              # Feature: Policy Management
│   ├── PolicyController.java
│   ├── PolicyService.java                  # Interface
│   ├── PolicyServiceImpl.java
│   ├── PolicyRepository.java
│   ├── Policy.java                         # insurerIds (set), policyType
│   ├── PolicyStatus.java                   # Enum: DRAFT, ACTIVE, EXPIRED, CANCELLED
│   ├── PolicyType.java                     # Enum: SINGLE_ENTRY_UP_TO_30_DAYS,
│   │                                       #       SINGLE_ENTRY_31_TO_60_DAYS,
│   │                                       #       IPMI_61_DAYS_TO_12_MONTHS
│   ├── PolicyMapper.java
│   └── 📁 dto/
│       ├── PolicyRequest.java
│       ├── PolicyResponse.java
│       └── PolicyDetailResponse.java       # PolicyResponse + embedded global benefit catalog
│
├── 📁 benefit/                             # Feature: Benefit Catalog (global)
│   ├── BenefitController.java
│   ├── BenefitService.java                 # Interface
│   ├── BenefitServiceImpl.java
│   ├── BenefitRepository.java
│   ├── Benefit.java                        # benefitName, limitAmount (no policy link)
│   ├── BenefitMapper.java
│   └── 📁 dto/
│       ├── BenefitRequest.java
│       └── BenefitResponse.java
│
├── 📁 visitor/                             # Feature: Visitor (insured traveler) Management
│   ├── VisitorController.java
│   ├── VisitorService.java                 # Interface
│   ├── VisitorServiceImpl.java
│   ├── VisitorRepository.java
│   ├── Visitor.java                        # policyId + passport-based KYC attributes,
│   │                                       # incl. address, facePhotoUrl, reasonForTravel,
│   │                                       # underlyingConditions
│   ├── VisitorCreatedEvent.java            # In-process event on visitor creation;
│   │                                       # consumed by visitorbenefit to seed benefits
│   ├── Gender.java                         # Enum: MALE, FEMALE, OTHER
│   ├── MaritalStatus.java                  # Enum: SINGLE, MARRIED, DIVORCED, WIDOWED
│   ├── VisitorMapper.java
│   └── 📁 dto/
│       ├── VisitorRequest.java
│       ├── VisitorResponse.java
│       └── VisitorDetailResponse.java      # KYC + assigned visitor benefits
│
├── 📁 visitorbenefit/                      # Feature: Benefits assigned to a visitor
│   ├── VisitorBenefitController.java
│   ├── VisitorBenefitService.java          # Interface
│   ├── VisitorBenefitServiceImpl.java
│   ├── VisitorBenefitRepository.java
│   ├── VisitorBenefit.java                 # visitorId, benefitId, limitAmount
│   ├── VisitorCreatedListener.java         # seeds visitor benefits from the
│   │                                       # catalog on VisitorCreatedEvent
│   ├── VisitorBenefitMapper.java
│   └── 📁 dto/
│       ├── VisitorBenefitRequest.java
│       └── VisitorBenefitResponse.java
│
├── 📁 preauthorization/                    # Feature: Pre-authorization Requests
│   ├── PreauthorizationController.java
│   ├── PreauthorizationService.java        # Interface
│   ├── PreauthorizationServiceImpl.java
│   ├── PreauthorizationRepository.java
│   ├── Preauthorization.java               # policyId, visitorId, icd11CodeId, benefitId,
│   │                                       # serviceProviderId, requestedAmount, approvedAmount
│   ├── PreauthorizationStatus.java         # Enum: PENDING, APPROVED, PARTIALLY_APPROVED,
│   │                                       #       REJECTED, EXPIRED
│   ├── PreauthorizationMapper.java
│   └── 📁 dto/
│       ├── PreauthorizationRequest.java
│       ├── PreauthorizationDecisionRequest.java   # Approve/reject with amount and reason
│       └── PreauthorizationResponse.java
│
├── 📁 claim/                               # Feature: Claims Processing
│   ├── ClaimController.java
│   ├── ClaimService.java                   # Interface
│   ├── ClaimServiceImpl.java
│   ├── ClaimRepository.java
│   ├── Claim.java                          # policyId, benefitId, serviceProviderId,
│   │                                       # preauthorizationId (nullable), claimedAmount,
│   │                                       # approvedAmount
│   ├── ClaimStatus.java                    # Enum: SUBMITTED, UNDER_REVIEW, APPROVED,
│   │                                       #       PARTIALLY_APPROVED, REJECTED, PAID
│   ├── ClaimMapper.java
│   └── 📁 dto/
│       ├── ClaimRequest.java
│       ├── ClaimDecisionRequest.java
│       └── ClaimResponse.java
│
├── 📁 icd11/                               # Feature: ICD-11 diagnosis code catalog
│   ├── Icd11CodeController.java
│   ├── Icd11CodeService.java               # Interface
│   ├── Icd11CodeServiceImpl.java
│   ├── Icd11CodeRepository.java
│   ├── Icd11Code.java                      # code (unique), title
│   ├── Icd11ExcelParser.java               # parses uploaded .xlsx → code/title rows
│   ├── Icd11CodeMapper.java
│   └── 📁 dto/
│       ├── Icd11CodeResponse.java
│       └── Icd11ImportResult.java          # totalRows, inserted, updated, skipped
│
├── 📁 department/                          # Feature: Department catalog
│   ├── DepartmentController.java
│   ├── DepartmentService.java              # Interface
│   ├── DepartmentServiceImpl.java
│   ├── DepartmentRepository.java
│   ├── Department.java                     # name (unique) — nothing else
│   ├── DepartmentMapper.java
│   └── 📁 dto/
│       ├── DepartmentRequest.java
│       └── DepartmentResponse.java
│
├── 📁 medicalservice/                      # Feature: Service catalog (belongs to a department)
│   ├── MedicalServiceController.java
│   ├── MedicalServiceService.java          # Interface
│   ├── MedicalServiceServiceImpl.java
│   ├── MedicalServiceRepository.java
│   ├── MedicalService.java                 # name, departmentId (unique per department)
│   ├── MedicalServiceExcelParser.java      # parses uploaded .xlsx → service/department rows
│   ├── MedicalServiceMapper.java
│   └── 📁 dto/
│       ├── MedicalServiceRequest.java
│       ├── MedicalServiceResponse.java
│       └── MedicalServiceImportResult.java  # totalRows, departmentsCreated,
│                                            # servicesInserted, servicesSkipped
│
└── TravelInsuranceApplication.java         # @SpringBootApplication entry point
```

## Core Insurance Flow

```
Benefit                                   (a global catalog of named benefits with limits,
                                           not scoped to any policy)

Policy
   │
   ├──1:N── Visitor ──1:N── VisitorBenefit  (the insured travelers and the benefits
   │            (KYC record;   assigned to them; each row references a global
   │             holds policyId) Benefit and snapshots its own limitAmount)
   │
   ├──1:N── Preauthorization ──0:1── Claim
   │            (provider asks for approval  (a claim may reference the
   │             before rendering a service)  pre-authorization that authorized it)
   └──1:N── Claim                          (claims may also arrive without a
                                            pre-authorization, e.g. reimbursement
                                            of out-of-pocket costs)
```

- A **Policy** is the insurance contract. It references a set of backing
  insurers (`insurerIds`) and carries a `policyType` and a status, but no
  cover dates of its own — one policy covers many visitors, each entering
  and leaving on their own schedule, so a fixed date range doesn't belong at
  the policy level. `policyType` is one of the three cover periods mandated
  by the Ministry of Health's Mandatory Inbound Travel Health Insurance
  framework (`PolicyType`: `SINGLE_ENTRY_UP_TO_30_DAYS`,
  `SINGLE_ENTRY_31_TO_60_DAYS`, `IPMI_61_DAYS_TO_12_MONTHS`), each carrying a
  min/max day range; it's enforced per visitor instead (see below). A policy
  holds no treatment-level detail. `GET /api/v1/policies/{id}` and the paged
  `GET /api/v1/policies` return `PolicyDetailResponse` rows that embed the
  benefit catalog under `benefits`; since benefits are global (see below),
  every policy carries the whole catalog. `PolicyController` fetches it once
  via `BenefitService.listAll()` and attaches it to each policy. Create/update
  return plain `PolicyResponse` rows without benefits.
- **Benefit** is a standalone **global catalog** entry: a `benefitName` (free
  text) and a `limitAmount` (limit of cover). It is no longer scoped to a
  policy — there is no `policyId` or fixed `BenefitType` enum. The catalog is
  managed directly through full CRUD (`POST/GET/PUT/DELETE /api/v1/benefits`);
  names are not required to be unique. Consumption is not tracked against the
  limit. Because there is no policy link, a policy's `benefits` in
  `PolicyDetailResponse` is simply the entire catalog. Other features
  reference a benefit by ID only: `VisitorBenefit`, `Preauthorization` and
  `Claim` validate that the referenced benefit exists (via
  `BenefitService.getEntityById`), but no longer that it belongs to a
  particular policy.
- **ICD-11 Code** is a reference catalog of diagnosis codes, each a unique
  `code` and a `title`. It is bulk-loaded by an admin uploading an `.xlsx`
  workbook to `POST /api/v1/icd11-codes/import` (multipart; `Icd11ExcelParser`
  locates the `code`/`title` header columns case-insensitively). The import
  upserts by `code` — existing codes are updated, new ones inserted, blank rows
  skipped — and returns an `Icd11ImportResult` count summary, so re-uploading
  the same file is idempotent. Lookups use `GET /api/v1/icd11-codes?query=…`
  (matches code or title, paged), `GET /api/v1/icd11-codes/search?title=…`
  (title-only substring match, paged — the diagnosis picker use case) and
  `GET /api/v1/icd11-codes/{code}`. Import is restricted to `ADMIN`; the read
  endpoints are open to any authenticated user.
- A **Department** is a plain name-only catalog entry (e.g. `PHARMACY`,
  `LABORATORY`) — nothing beyond the `BaseEntity` fields and a unique `name`.
  A **MedicalService** belongs to exactly one department, referenced by
  `departmentId` (ID-only, same convention as every other cross-feature
  reference — no JPA relation), and its `name` is unique per department rather
  than globally, so two departments may each have an identically-named
  service. `GET /api/v1/departments/{id}` never embeds that department's
  services — callers fetch them separately via
  `GET /api/v1/medical-services?departmentId=…` (paged) or
  `GET /api/v1/medical-services/by-department/{departmentId}` (unpaged),
  keeping department reads cheap regardless of catalog size.
  `MedicalServiceResponse` additionally carries the owning department's
  `departmentName` (resolved through `DepartmentService.namesByIds`; `null` if
  the department has since been deleted), the same "resolve the display name,
  don't nest the entity" shape already used by `VisitorBenefitResponse`.
  Both catalogs are bulk-loaded from the master list: an admin uploads a
  two-column (`service`/`department`) `.xlsx` workbook to
  `POST /api/v1/medical-services/import` (multipart;
  `MedicalServiceExcelParser` locates the header columns case-insensitively,
  mirroring `Icd11ExcelParser`). For each row, `MedicalServiceServiceImpl`
  resolves the department by exact name — creating it via
  `DepartmentService.findOrCreateByName` if it doesn't exist yet, caching the
  lookup within the run so a department referenced by hundreds of rows is
  only resolved once — then upserts the service by (`name`, `departmentId`);
  rows with a blank service or department name, or a service already present
  in that department, are skipped. The returned `MedicalServiceImportResult`
  reports `totalRows`/`departmentsCreated`/`servicesInserted`/`servicesSkipped`,
  so re-uploading the same file is idempotent (a second run reports zero
  inserted/created). Writes (create/update/delete/import) on both
  `/api/v1/departments` and `/api/v1/medical-services` are restricted to
  `ADMIN`; reads are open to any authenticated user.
- A **Visitor** is an insured traveler behind a policy. It carries a
  `policyId` (ID-only reference — one policy may cover many visitors) plus the
  passport-based basic KYC attributes captured at onboarding: full name,
  passport number (unique), date of birth, gender, nationality, address,
  email, phone number, date in / date out of the country, marital status,
  reason for travel, underlying condition/prescribed-medicine notes
  (`underlyingConditions`, nullable), a face photo upload (`facePhotoUrl`),
  and next of kin (name + phone) — aligned with the e-portal ("Kenya Cares")
  onboarding data set required by the framework. `Gender` and `MaritalStatus`
  are string-mapped enums. `dateIn`/`dateOut` is where the mandated cover
  period actually gets enforced: `VisitorServiceImpl` fetches the visitor's
  policy and rejects a create/update where `dateOut` is before `dateIn`, or
  where the day span between them falls outside the policy's `PolicyType`
  range — `IllegalArgumentException` (→ 400) either way.
  `GET /api/v1/visitors/{id}` and
  `GET /api/v1/visitors/by-passport?passportNumber=…` return a
  `VisitorDetailResponse` that embeds the visitor's assigned benefits
  (`visitorBenefits`), and `GET /api/v1/visitors/by-policy?policyId=…`
  returns a list of them (one per visitor on the policy) — composed in
  `VisitorController` from `VisitorBenefitService`, which keeps service
  dependencies acyclic. The paged list and create/update endpoints return
  plain `VisitorResponse` rows without benefits.
- A visitor carries a `VisitorStatus` with guarded transitions
  (`canTransitionTo`). A newly created visitor defaults to `ACTIVE`. It is updated via
  `PATCH /api/v1/visitors/{id}/status` or
  `PATCH /api/v1/visitors/by-passport/status?passportNumber=…`, both taking a
  `VisitorStatusUpdate` body; an allowed transition publishes a
  `VisitorStatusChangedEvent`, an invalid one is rejected with `409 Conflict`.
- Visitors are auto-assigned the full benefit catalog on creation:
  `VisitorServiceImpl` publishes an in-process `VisitorCreatedEvent`, which
  `visitorbenefit.VisitorCreatedListener` consumes to create one
  `VisitorBenefit` per global `Benefit` (each snapshotting the catalog
  `limitAmount` and taking the visitor's current status, `ACTIVE` by default).
  The listener skips benefits already
  assigned to the visitor, so it is idempotent. Further benefits can still be
  attached explicitly via the `VisitorBenefit` endpoints.
- A **VisitorBenefit** assigns a global catalog benefit to a visitor. It
  carries `visitorId`, `benefitId`, and its own `limitAmount` — snapshotted
  from the `Benefit` at assignment time unless an explicit limit is supplied —
  so later catalog edits do not alter benefits already assigned to a visitor.
  The referenced benefit only needs to exist (no policy-membership check). A
  visitor may hold each catalog benefit at most once (`visitorId` + `benefitId`
  unique). Usage tracking against the limit is out of scope for now.
  `VisitorBenefitResponse` additionally carries the catalog benefit's
  `benefitName` (resolved through `BenefitService`; `null` if the catalog
  benefit has since been deleted) so clients can display assignments without
  extra lookups.
- A **Preauthorization** is raised by a `PROVIDER_USER` before rendering a
  service and is decided by an `INSURER_USER` (or a admin agent). Create
  requires the diagnosis (`icd11CodeId`, validated via `Icd11CodeService`),
  the patient (`visitorId`, validated via `VisitorService`, existence only —
  not checked against the request's `policyId`), the accessed hospital
  (`serviceProviderId`, validated via `ServiceProviderService`), the services
  rendered (`serviceDescription`), the utilised `benefitId`, and
  `requestedAmount`. On `decide`, the approver and decision time are not
  separate columns — they reuse `BaseEntity`'s existing `updatedBy`/`updatedDate`
  audit columns (already populated by `AuditorAware` on every save) and are
  surfaced in `PreauthorizationResponse` as `decidedBy`/`decidedAt`, `null`
  while the request is still `PENDING`. `PreauthorizationResponse` also
  resolves display names for every referenced ID — `policyNumber`,
  `visitorName`, `icd11Code`/`icd11Title`, `benefitName`,
  `serviceProviderName` — via the respective feature services, so API
  consumers never have to display a raw UUID.
- A **Claim** is the request for payment. It is either provider-submitted
  against an approved pre-authorization, or customer-submitted for
  reimbursement (no pre-authorization). Decisions are made by the insurer;
  `PAID` is the terminal status.
- Cross-feature references are **ID columns only** (the same rule as
  `User.organizationId`): the `claim` feature calls `PolicyService` and
  `BenefitService`, never their repositories, and no JPA relations cross
  package boundaries.

## Procedures (Catalogue & Excel Upload)

The `procedure` feature is a catalogue of service items (e.g. `Nebulization`,
`Lumbar Puncture`) scoped to a department. It is independent of the insurance
flow above.

- A **Procedure** carries a generated `procedureCode`, a display `name`, an
  internal `normalizedName` (never exposed), an optional `description`, a
  `departmentPublicId` (ID-only reference — **no** JPA relationship to the
  department feature), an `active` flag and a nullable `uploadBatchPublicId`
  (set on rows created by Excel import). The entity's UUID `id` is its public id.
- **Codes** come from a dedicated Postgres sequence (`procedure_code_seq`, via
  `ProcedureRepository.nextProcedureCodeValue()`), formatted `PRC-0001` by
  `ProcedureCodeGenerator`. The same generator serves manual creation and Excel
  import; codes are never derived from a count/`MAX+1`, never editable, never
  reused (gaps are fine). Uniqueness is enforced by `uq_procedures_code`.
- **Name cleaning/normalization** lives in `ProcedureNameNormalizer` and is shared
  by manual create, update, Excel validation and Excel import: trim, collapse
  whitespace, replace non-breaking spaces, strip control/format characters; the
  cleaned value is the display name and its upper-cased form the normalized name.
  Medical terminology is never altered.
- **Duplicate rule**: unique on `departmentPublicId + normalizedName`, enforced in
  the application and by a partial unique index (`where deleted = false`). An
  active match is rejected (`409`); an inactive match is rejected advising
  reactivation. Activation re-checks for an active conflict first.
- **Department validation** goes through the `department.DepartmentService`
  interface (`getEntityById(UUID)`), which throws `ResourceNotFoundException`
  (`404`) for an unknown department id — no repository reach-across. The
  Department entity has no active flag, so validation is existence-only.
- **Endpoints**: CRUD + search/filter (`GET /api/v1/procedures?search=&departmentPublicId=&active=`,
  paged/sortable), `PATCH /{id}/activate`, `PATCH /{id}/deactivate` (no hard
  delete in normal operation).

Bulk creation is a synchronous two-stage Excel flow under
`/api/v1/procedures/uploads` (`procedure.upload`):

- The **department is chosen per row, inside the file**. The template is
  `Procedure Name*` | `Department*` | `Description`. Each row's department **name**
  is resolved to a department id **case-insensitively and trimmed**, in one bulk
  query for the whole file (`DepartmentService.idsByName`). A blank department cell
  fails the row (`DEPARTMENT_REQUIRED`); an unmatched name fails the row
  (`DEPARTMENT_NOT_FOUND`) — departments are never auto-created. In-file and DB
  duplicate detection is keyed by **department + normalized name**, so the same
  name under two departments is not a duplicate.
- **Validate** (`POST /upload`, multipart `file` only) reads the whole workbook once
  (`ProcedureExcelParser`, preserving real Excel row numbers, formulas never
  evaluated), detects in-file duplicates via in-memory maps, bulk-loads existing
  matches with one query, classifies each row (`VALID` / `SKIPPED` (already
  exists) / `FAILED` (name required, too long, duplicate-in-file, inactive
  exists)), persists a `ProcedureUpload` + `ProcedureUploadRow` rows, and returns
  a summary. No procedures are created.
- **Import** (`POST /upload/{uploadPublicId}/import`) is guarded against repeat/parallel
  runs by status transitions (`RECEIVED → VALIDATING → READY_FOR_IMPORT →
  PROCESSING → COMPLETED[/_WITH_ERRORS]/FAILED`), re-checks duplicates immediately
  before saving, generates a code per new procedure, sets the upload-batch id,
  and persists in batches (`hibernate.jdbc.batch_size`). A late uniqueness race
  surfaces as a `409`.
- **Per-row result & storage**: each `ProcedureUploadRow` stores the cleaned
  `procedure_name`, the submitted `department` and `description`, and the resolved
  `department_public_id` — the raw submitted name and the normalized name are **not**
  persisted (the normalized form is recomputed deterministically at import time for
  the duplicate re-check). Both the validate and import responses expose a single
  cleaned **`name`** per row (with `department`/`description` echoes and the row
  status/error), consistent with the `name` field on the main procedure responses.
- **Downloads**: `GET /upload/download` (cached static template bytes) and
  `GET /upload/{uploadPublicId}/errors` (failed/skipped rows only, with a Department column), both
  streamed as `.xlsx` attachments with the correct content type.
- Operational limits (`procedure.upload.*` → `ProcedureUploadProperties`): max
  file size, max rows, batch size, max name length. Background/`@Async` +
  streaming (SXSSF) processing for very large files is intentionally deferred.

## Users, Roles & Organizations

A single `User` entity serves everyone — admin staff, insurer staff, and
service provider staff. Users are distinguished by role, not by separate
entities:

- `User.roles` is a `Set<Role>` (enum, `@ElementCollection`) mapped to Spring
  Security authorities.
- Users belonging to an external organization carry a plain
  `organizationId: UUID` together with the discriminating role
  (`INSURER_USER` → ID of an `Insurer`, `PROVIDER_USER` → ID of a
  `ServiceProvider`). This is a plain column, **not** a JPA relation, so the
  `user` package stays decoupled from `insurer` and `serviceprovider`.
- Data scoping is enforced in the service layer: for example, an
  `INSURER_USER` may only see policies and claims where
  `insurerIds` contains `user.organizationId`. Roles gate *which endpoints* a user can
  call; `organizationId` gates *which rows* they can see.
- The `auth` feature owns login and JWT concerns and depends on `user`
  (service → service); `config/SecurityConfig` wires the JWT filter and
  role-based route rules.

## Layering Rules

- **Controller → Service → Repository**. Never skip a layer or call backwards.
- Every service is split into an **interface** (`XxxService`) and an
  **implementation** (`XxxServiceImpl`, annotated `@Service`). Controllers and
  other features depend only on the interface; the implementation is an
  injection-time detail. All business logic lives in the implementation.
- Controllers accept and return **DTOs only** — entities never cross the web
  boundary.
- Mappers convert between entities and DTOs so persistence details stay inside
  the feature.
- Cross-feature calls go **service → service** (for example,
  `ServiceProviderServiceImpl` may use `InsurerService`, never
  `InsurerRepository`).
- `common/` and `config/` must not depend on any feature package.

## Base Entity, Auditing & Soft Delete

Every entity extends `common/domain/BaseEntity` (`@MappedSuperclass`):

| Field | Type | Notes |
|---|---|---|
| `id` | `UUID` | Primary key, generated |
| `deleted` | `boolean` | Soft-delete flag, defaults to `false` |
| `createdDate` | `Instant` | `@CreatedDate` |
| `updatedDate` | `Instant` | `@LastModifiedDate` |
| `deletedDate` | `Instant` | Set when `deleted` flips to `true` |
| `createdBy` | `UUID` | `@CreatedBy` — public ID of the acting user |
| `updatedBy` | `UUID` | `@LastModifiedBy` — public ID of the acting user |

- Auditing is driven by `JpaAuditingConfig`, whose `AuditorAware<UUID>` reads
  the current user's public ID from the Spring Security context.
- **Soft delete**: repositories never hard-delete. Entities are annotated with
  `@SQLDelete(sql = "... set deleted = true, deleted_date = now() ...")` and
  `@SQLRestriction("deleted = false")`, so deleted rows drop out of queries
  automatically.

## Conventions

- Package names are lowercase and singular (`user`, `insurer`,
  `serviceprovider`).
- REST base paths are plural kebab-case (`/api/v1/users`,
  `/api/v1/service-providers`).
- Request DTOs are validated with `jakarta.validation` (`@Valid` in
  controllers).
- All errors are normalized to `ApiError` by `GlobalExceptionHandler`, always
  serialized as `application/json` regardless of the request's negotiated content
  type (so error bodies never fail on non-JSON `Accept`/path extensions).
- Database schema changes ship as Flyway migrations
  (`src/main/resources/db/migration/V###__description.sql`); Hibernate
  `ddl-auto` is never used to manage the schema.

## REST Resources

| Feature           | Base Path                     | Entity Table        |
|-------------------|-------------------------------|---------------------|
| User              | `/api/v1/users`               | `users`             |
| Insurer           | `/api/v1/insurers`            | `insurers`          |
| Service Provider  | `/api/v1/service-providers`   | `service_providers` |
| Policy            | `/api/v1/policies`            | `policies`          |
| Benefit           | `/api/v1/benefits`            | `benefits`          |
| Visitor           | `/api/v1/visitors`            | `visitors`          |
| Visitor Benefit   | `/api/v1/visitor-benefits`    | `visitor_benefits`  |
| Pre-authorization | `/api/v1/preauthorizations`   | `preauthorizations` |
| Claim             | `/api/v1/claims`              | `claims`            |
| ICD-11 Code       | `/api/v1/icd11-codes`         | `icd11_codes`       |
| Procedure         | `/api/v1/procedures`          | `procedures`        |
| Procedure Upload  | `/api/v1/procedures/uploads`  | `procedure_uploads`, `procedure_upload_rows` |
| Department        | `/api/v1/departments`         | `departments`       |
| Medical Service   | `/api/v1/medical-services`    | `medical_services`  |

## Policy Tokenization (Quota Management)

The system enforces **per-insurer policy quotas** to prevent insurers from overselling policies.

**Concept:**
- Each `Insurer` is allocated a fixed number of policies via `policyToken` (e.g., 1000 policies for Minet Insurance)
- When a `Visitor` is created using a policy backed by an insurer, that insurer's available quota decreases by 1
- The system prevents visitor creation if any backing insurer has exhausted their quota (policyToken ≤ 0)
- If a visitor is deleted, the quota is restored

**Data Model:**
- `Insurer.policyToken: Long` — available (unconsumed) policies for this insurer
- `InsurerResponse.availablePolicies: Long` — exposed in API responses (same as policyToken, defaults to 0 if null)

**Event-Driven Flow:**

1. **Visitor Creation → Policy Consumption**
   - `VisitorServiceImpl.create()` validates that all backing insurers have `policyToken > 0`
   - If validation passes, visitor is saved and `VisitorCreatedEvent` is published
   - `PolicyConsumptionListener` receives the event and decrements `policyToken` for each backing insurer
   - Example: Minet Insurance 1000 → 999 when first visitor is created

2. **Visitor Deletion → Policy Restoration**
   - `VisitorServiceImpl.delete()` soft-deletes the visitor and publishes `VisitorDeletedEvent`
   - `PolicyRestorationListener` receives the event and restores (increments) `policyToken` for each backing insurer
   - Example: Minet Insurance 999 → 1000 when that visitor is deleted

3. **Quota Exhaustion**
   - When `policyToken` reaches 0, any attempt to create a visitor using that insurer's policy fails with:
   - `IllegalStateException: "Insurer 'Minet Insurance' has no available policies left"`
   - HTTP 400 (Bad Request)

**Implementation Details:**

| Component | Responsibility |
|-----------|-----------------|
| `PolicyConsumptionListener` | Listens to `VisitorCreatedEvent`; decrements `policyToken` for all backing insurers |
| `PolicyRestorationListener` | Listens to `VisitorDeletedEvent`; restores `policyToken` for all backing insurers |
| `VisitorServiceImpl.validatePolicyQuota()` | Pre-creation validation; checks all insurers have available policies |
| `VisitorServiceImpl.delete()` | Publishes `VisitorDeletedEvent` after soft-delete |
| `InsurerResponse.availablePolicies` | Exposes quota count in API responses for admin monitoring |

**Example API Usage:**

```bash
# Create insurer with 1000 policies
POST /api/v1/insurers
{
  "name": "Minet Insurance",
  "policyToken": 1000
}

# Create policy linked to insurer
POST /api/v1/policies
{
  "policyNumber": "POL-001",
  "insurerIds": ["<insurer-id>"],
  "policyType": "SINGLE_ENTRY_UP_TO_30_DAYS",
  "status": "ACTIVE"
}

# Create first visitor → Minet.policyToken: 1000 → 999
POST /api/v1/visitors
{ "policyId": "<policy-id>", ... }

# Create second visitor → Minet.policyToken: 999 → 998
POST /api/v1/visitors
{ "policyId": "<policy-id>", ... }

# Check available policies
GET /api/v1/insurers/<insurer-id>
# Response includes: "availablePolicies": 998

# After 1000 visitors created, next attempt fails
POST /api/v1/visitors
# 400 Bad Request: "Insurer 'Minet Insurance' has no available policies left"
```

**Multi-Insurer Policies:**

Policies can be backed by multiple insurers (via `insurerIds` collection). When a visitor is created:
- ALL backing insurers' quotas are decremented
- If ANY insurer has exhausted quota, visitor creation is rejected
- All insurers must have available policies for the visitor to succeed

Example:
- Policy ABC backed by Insurer A (500 policies) and Insurer B (200 policies)
- Creating a visitor decrements both: A: 500→499, B: 200→199
- When B reaches 0 but A has 100+ left, visitor creation still fails because B is exhausted

## Messaging (RabbitMQ)

- Uses `spring-boot-starter-amqp`, with exchanges, queues, and bindings
  declared in `config/RabbitConfig.java`.
- Services publish domain events through `common/messaging/EventPublisher`
  (a thin wrapper over `RabbitTemplate`) — for example `claim.approved`,
  `preauthorization.decided`, and `policy.activated` — to drive notifications
  and downstream integrations.
- Listeners (`@RabbitListener`) live inside the feature package that consumes
  the event.

## Notifications (Policy Document Email)

When a `Visitor`'s cover becomes `ACTIVE`, the `notification` package
emails them a personalized policy certificate as a PDF attachment:

- `VisitorActivatedNotificationListener` sends the certificate on two paths,
  both gated on `ACTIVE`: `VisitorStatusChangedEvent` with `newStatus == ACTIVE`
  (a transition), and `VisitorCreatedEvent` when the newly created visitor is
  already `ACTIVE` (the default status), so visitors created active still get a
  certificate without a separate activation step. Unlike
  the sibling `visitorbenefit.VisitorStatusChangedListener` (which stays
  synchronous and in-transaction because it must mirror the status onto
  `VisitorBenefit` rows consistently), this listener uses
  `@TransactionalEventListener(phase = AFTER_COMMIT)`: sending mail over SMTP
  inside the same transaction that changed the visitor's status would risk
  rolling back a legitimate status change if the mail server is slow or
  unreachable. Any failure here is caught and logged, never propagated — a
  broken mail server must never affect the visitor status API's correctness.
  Re-activation (e.g. `ACTIVE` → `SUSPENDED` → `ACTIVE`) intentionally
  re-sends the certificate; that's treated as a new, valid activation, not a
  duplicate to guard against.
- The listener composes data via `VisitorService`, `PolicyService`,
  `VisitorBenefitService`, and `InsurerService` (the same "fan-in at a
  boundary" shape already used for `VisitorDetailResponse`), builds a
  `PolicyDocumentData` holder (internal to
  the package, not a DTO — it never crosses the web boundary), and passes it
  to `PolicyDocumentRenderer`. The underwriter logo comes from the first
  backing insurer with a non-blank `Insurer.logoUrl`; when present the template
  renders it as an `<img>`, otherwise it falls back to the dashed placeholder.
  Logo URLs are normalized by `common.util.LogoUrlNormalizer` so they return
  raw image bytes: Dropbox share links (`www.dropbox.com/...?dl=0`, which serve
  an HTML preview the PDF renderer can't read) are rewritten to the
  `dl.dropboxusercontent.com` direct-download host with `dl=1`. Normalization
  is applied both on save (`InsurerMapper`) and defensively at render time (for
  any logo stored before this was added).
- `PolicyDocumentRenderer` has no dependency on any other feature's service —
  it only knows how to render `templates/policy-certificate.html` (Thymeleaf) to
  HTML, then converts that HTML to PDF bytes via `openhtmltopdf`. The
  template deliberately excludes `Visitor.underlyingConditions`: none of the
  real insurer certificates this template is modeled on embed a free-text
  medical-conditions field, and there's no reason to widen PII exposure over
  email with it (see `policy-document-analysis.md` for the full reference
  analysis).
- The activation email carries two attachments: the personalized
  `policy-certificate-<policyNumber>.pdf` (rendered per visitor) and the static
  policy wording `templates/Policy_Document_July_2026.pdf`, loaded once from the
  classpath and cached. If the bundled document can't be read it is logged and
  skipped so the certificate still goes out.
- `common/email/EmailService` is a thin, domain-agnostic wrapper over
  `JavaMailSender` (mirrors `common/messaging/EventPublisher`'s catch-and-log
  style) — it never logs the email body or PDF bytes, only the outcome. It
  accepts either a single attachment or a `List<EmailAttachment>`.
- SMTP config (`spring.mail.*`) is sourced from `SMTP_*` env vars with
  STARTTLS explicitly required (Spring Boot does not enable it by default);
  `app.mail.from` and `app.mail.emergency-assistance.{phone,email}`
  (`config/MailProperties.java`) hold the small amount of static content our
  domain model doesn't capture (a 24/7 helpline is not stored per policy —
  every reference insurer hardcodes it too).
- No "document sent" tracking column exists — a resend on re-activation is
  desired behavior, not a defect.

## API Documentation (Swagger)

- Uses `springdoc-openapi-starter-webmvc-ui`: the UI is served at
  `/swagger-ui.html` and the spec at `/v3/api-docs`.
- `config/OpenApiConfig.java` holds the API metadata and the JWT bearer
  security scheme, so the **Authorize** button works in the UI.
- Both endpoints are permitted in `SecurityConfig` for non-production profiles
  only.

## Security

- The JWT signing key is a strong secret of at least 256 bits, supplied via an
  environment variable or secret manager — never committed to the repository
  or `application.yml`.
- Access tokens are short-lived and paired with refresh tokens (handled by the
  `auth` feature).
- Passwords are hashed with BCrypt (`PasswordEncoder` bean in
  `SecurityConfig`).

## Database Connection Pool (HikariCP)

Spring Boot's default pool is HikariCP. The sizing rule of thumb is
`pool size = (2 × CPU cores) + effective disk spindles`; more connections than
this degrades throughput rather than improving it.

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 10       # Good default for a 4-core host with SSD storage
      minimum-idle: 10            # Keep the pool fixed-size; avoids resize churn
      connection-timeout: 30000   # ms
      max-lifetime: 1800000       # 30 min, below typical DB/firewall timeouts
```

Revisit `maximum-pool-size` only with load-test evidence, and ensure
PostgreSQL's `max_connections` comfortably exceeds
`pool size × application instances`.

## Code Practices

- **Thin controllers**: no business logic in controllers. They validate input
  (`@Valid`), delegate to the service, and map the result to a response.
- **Small, single-purpose methods**: aim for a maximum of ~20 lines; each
  method does one specific task. Extract private helpers rather than growing a
  method.
- **Unit tests** for every service (Mockito for collaborators) and mapper;
  controller slice tests with `@WebMvcTest` and `spring-security-test`;
  repository tests against H2.
- **Lombok** for boilerplate (`@Getter`, `@Builder`,
  `@RequiredArgsConstructor` for constructor injection). Avoid `@Data` on
  entities.

## Maven Dependencies

Parent: `spring-boot-starter-parent` 3.x. Explicit versions are only needed
where Spring Boot's dependency management does not provide one.

| Dependency | Scope | Purpose |
|---|---|---|
| `spring-boot-starter-web` | compile | REST controllers |
| `spring-boot-starter-data-jpa` | compile | Repositories, entities |
| `spring-boot-starter-validation` | compile | `@Valid` on request DTOs |
| `spring-boot-starter-security` | compile | Auth filter chain, role-based access |
| `spring-boot-starter-amqp` | compile | RabbitMQ messaging |
| `spring-boot-starter-mail` | compile | SMTP sending (`common/email/EmailService`) |
| `spring-boot-starter-thymeleaf` | compile | Policy document HTML templating |
| `io.github.openhtmltopdf:openhtmltopdf-pdfbox` | compile | HTML → PDF rendering for the emailed policy certificate |
| `org.springdoc:springdoc-openapi-starter-webmvc-ui` (2.6.x) | compile | Swagger UI + OpenAPI docs |
| `org.postgresql:postgresql` | runtime | Production database driver |
| `io.jsonwebtoken:jjwt-api` (0.12.x) | compile | JWT for `JwtTokenProvider` |
| `io.jsonwebtoken:jjwt-impl` (0.12.x) | runtime | JWT implementation |
| `io.jsonwebtoken:jjwt-jackson` (0.12.x) | runtime | JWT JSON serialization |
| `org.flywaydb:flyway-core` + `flyway-database-postgresql` | compile | Schema migrations |
| `org.projectlombok:lombok` | provided (optional) | Boilerplate reduction |
| `spring-boot-starter-test` | test | JUnit 5, Mockito, AssertJ |
| `spring-security-test` | test | `@WithMockUser`, security test support |
| `com.h2database:h2` | test | In-memory database for tests |

```xml
<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-security</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-amqp</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-mail</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-thymeleaf</artifactId>
    </dependency>
    <dependency>
        <groupId>io.github.openhtmltopdf</groupId>
        <artifactId>openhtmltopdf-pdfbox</artifactId>
        <version>1.1.70</version>
    </dependency>
    <dependency>
        <groupId>org.springdoc</groupId>
        <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
        <version>2.6.0</version>
    </dependency>

    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
        <scope>runtime</scope>
    </dependency>
    <dependency>
        <groupId>org.flywaydb</groupId>
        <artifactId>flyway-core</artifactId>
    </dependency>
    <dependency>
        <groupId>org.flywaydb</groupId>
        <artifactId>flyway-database-postgresql</artifactId>
    </dependency>

    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-api</artifactId>
        <version>0.12.6</version>
    </dependency>
    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-impl</artifactId>
        <version>0.12.6</version>
        <scope>runtime</scope>
    </dependency>
    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-jackson</artifactId>
        <version>0.12.6</version>
        <scope>runtime</scope>
    </dependency>

    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <optional>true</optional>
    </dependency>

    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.springframework.security</groupId>
        <artifactId>spring-security-test</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>com.h2database</groupId>
        <artifactId>h2</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```