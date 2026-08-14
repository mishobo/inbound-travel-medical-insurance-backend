package com.travel.insurance.procedure.upload;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ProcedureUploadRowRepository extends JpaRepository<ProcedureUploadRow, UUID> {

    List<ProcedureUploadRow> findByUploadIdAndRowStatusInOrderByExcelRowNumberAsc(UUID uploadId, Collection<ProcedureRowStatus> rowStatuses);
}
