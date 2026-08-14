package com.travel.insurance.procedure.upload;

import com.travel.insurance.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.util.UUID;

/**
 * One spreadsheet row and its validation/import outcome, linked to a
 * {@link ProcedureUpload} by id (no JPA relationship). Preserves the actual Excel
 * row number for traceable error reporting.
 */
@Entity
@Table(name = "procedure_upload_rows")
@SQLDelete(sql = "update procedure_upload_rows set deleted = true, deleted_date = now() where id = ?")
@SQLRestriction("deleted = false")
@Getter
@Setter
@NoArgsConstructor
public class ProcedureUploadRow extends BaseEntity {

    @Column(name = "upload_id", nullable = false)
    private UUID uploadId;

    @Column(name = "excel_row_number", nullable = false)
    private int excelRowNumber;

    private String procedureName;
    private String department;
    private String description;

    @Column(name = "department_public_id")
    private UUID departmentPublicId;

    @Enumerated(EnumType.STRING)
    @Column(name = "row_status", nullable = false)
    private ProcedureRowStatus rowStatus;

    private String errorCode;
    private String errorMessage;

    @Column(name = "created_procedure_public_id")
    private UUID procedurePublicId;
}
