-- Collapse the per-row name variants down to a single procedure_name and align
-- the remaining submitted columns with the entity field names. The submitted
-- (raw) name is no longer retained separately, and the normalized name is
-- recomputed deterministically at import time rather than persisted.

alter table procedure_upload_rows rename column cleaned_name to procedure_name;
alter table procedure_upload_rows rename column submitted_description to description;
alter table procedure_upload_rows rename column submitted_department to department;

alter table procedure_upload_rows drop column submitted_name;
alter table procedure_upload_rows drop column normalized_name;
