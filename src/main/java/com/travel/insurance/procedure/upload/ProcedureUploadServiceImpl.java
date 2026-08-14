package com.travel.insurance.procedure.upload;

import com.travel.insurance.common.exception.ResourceNotFoundException;
import com.travel.insurance.common.util.SecurityUtils;
import com.travel.insurance.config.ProcedureUploadProperties;
import com.travel.insurance.department.DepartmentService;
import com.travel.insurance.procedure.Procedure;
import com.travel.insurance.procedure.ProcedureCodeGenerator;
import com.travel.insurance.procedure.ProcedureMapper;
import com.travel.insurance.procedure.ProcedureNameNormalizer;
import com.travel.insurance.procedure.ProcedureNameNormalizer.CleanedName;
import com.travel.insurance.procedure.ProcedureRepository;
import com.travel.insurance.procedure.upload.ProcedureExcelParser.ProcedureExcelRow;
import com.travel.insurance.procedure.upload.dto.ProcedureImportResponse;
import com.travel.insurance.procedure.upload.dto.ProcedureUploadResponse;
import com.travel.insurance.procedure.upload.dto.ProcedureUploadValidationResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Two-stage procedure Excel upload (validate then import) with per-row tracking.
 *
 * <p>Each spreadsheet row names its own department; the name is resolved to a
 * department id case-insensitively, in one bulk query for the whole file. The
 * duplicate rule (department + normalized name), name cleaning/normalization and
 * code generator are reused from manual creation; this class only adds file
 * parsing, bulk validation, batch persistence and row-level reporting.
 * Background/streaming processing for very large files is deferred — this path is
 * synchronous.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class ProcedureUploadServiceImpl implements ProcedureUploadService {

    private final DepartmentService departmentService;
    private final ProcedureExcelParser excelParser;
    private final ProcedureNameNormalizer nameNormalizer;
    private final ProcedureCodeGenerator codeGenerator;
    private final ProcedureMapper procedureMapper;
    private final ProcedureRepository procedureRepository;
    private final ProcedureUploadRepository uploadRepository;
    private final ProcedureUploadRowRepository rowRepository;
    private final ProcedureUploadMapper uploadMapper;
    private final ProcedureUploadWorkbooks workbooks;
    private final ProcedureUploadProperties properties;

    @Override
    public ProcedureUploadValidationResponse validate(MultipartFile file) {
        validateFile(file);
        List<ProcedureExcelRow> parsed = parse(file);

        ProcedureUpload upload = newUpload(file);
        uploadRepository.saveAndFlush(upload);

        List<ProcedureUploadRow> rows = evaluateRows(parsed, upload.getId());
        applyValidationCounts(upload, parsed.size(), rows);
        rowRepository.saveAll(rows);
        uploadRepository.save(upload);

        return uploadMapper.toValidationResponse(upload, rows);
    }

    @Override
    public ProcedureImportResponse importUpload(UUID uploadPublicId) {
        ProcedureUpload upload = getUploadEntity(uploadPublicId);
        guardImportEligible(upload);
        upload.setStatus(ProcedureUploadStatus.PROCESSING);
        upload.setProcessingStartTime(Instant.now());
        uploadRepository.saveAndFlush(upload);

        List<ProcedureUploadRow> rows = rowRepository.findByUploadIdOrderByExcelRowNumberAsc(uploadPublicId);
        List<ProcedureUploadRow> candidates = rows.stream()
                .filter(row -> row.getRowStatus() == ProcedureRowStatus.VALID)
                .toList();
        List<ProcedureUploadRow> toCreate = recheckDuplicates(candidates);
        createInBatches(toCreate, upload);

        applyImportCounts(upload, rows);
        rowRepository.saveAll(rows);
        uploadRepository.save(upload);
        return uploadMapper.toImportResponse(upload, rows);
    }

    @Override
    @Transactional(readOnly = true)
    public ProcedureUploadResponse getUpload(UUID uploadPublicId) {
        return uploadMapper.toUploadResponse(getUploadEntity(uploadPublicId));
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] template() {
        return workbooks.template();
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] errorReport(UUID uploadPublicId) {
        getUploadEntity(uploadPublicId);
        List<ProcedureUploadRow> rows = rowRepository.findByUploadIdAndRowStatusInOrderByExcelRowNumberAsc(
                uploadPublicId, List.of(ProcedureRowStatus.FAILED, ProcedureRowStatus.SKIPPED));
        return workbooks.errorReport(rows);
    }

    // --- file / parsing ---

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("The uploaded file is empty");
        }
        if (file.getSize() > properties.getMaxFileSizeBytes()) {
            throw new IllegalArgumentException(
                    "The uploaded file exceeds the maximum size of " + properties.getMaxFileSizeBytes() + " bytes");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw new IllegalArgumentException("The uploaded file must be a .xlsx workbook");
        }
    }

    private List<ProcedureExcelRow> parse(MultipartFile file) {
        List<ProcedureExcelRow> parsed;
        try {
            parsed = excelParser.parse(file.getInputStream());
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to read the uploaded file", e);
        }
        if (parsed.size() > properties.getMaxRows()) {
            throw new IllegalArgumentException("The uploaded file has " + parsed.size()
                    + " rows which exceeds the maximum of " + properties.getMaxRows());
        }
        return parsed;
    }

    private ProcedureUpload newUpload(MultipartFile file) {
        ProcedureUpload upload = new ProcedureUpload();
        upload.setOriginalFilename(file.getOriginalFilename());
        upload.setStatus(ProcedureUploadStatus.VALIDATING);
        upload.setUploadedBy(SecurityUtils.currentUserId().orElse(null));
        return upload;
    }

    // --- validation ---

    private List<ProcedureUploadRow> evaluateRows(List<ProcedureExcelRow> parsed, UUID uploadId) {
        List<PreparedRow> prepared = prepare(parsed);
        Map<String, Procedure> existing = loadExisting(groupNormalizedByDepartment(prepared));

        Map<String, Integer> firstRowByKey = new HashMap<>();
        List<ProcedureUploadRow> rows = new ArrayList<>(prepared.size());
        for (PreparedRow row : prepared) {
            rows.add(evaluateRow(row, uploadId, existing, firstRowByKey));
        }
        return rows;
    }

    /** Cleans each row's name and resolves its department name to an id in one bulk query. */
    private List<PreparedRow> prepare(List<ProcedureExcelRow> parsed) {
        Set<String> departmentKeys = parsed.stream()
                .map(raw -> departmentKey(raw.department()))
                .filter(key -> !key.isEmpty())
                .collect(java.util.stream.Collectors.toSet());
        Map<String, UUID> departmentIds = departmentKeys.isEmpty()
                ? Map.of() : departmentService.idsByName(departmentKeys);

        List<PreparedRow> prepared = new ArrayList<>(parsed.size());
        for (ProcedureExcelRow raw : parsed) {
            CleanedName name = nameNormalizer.clean(raw.name());
            String key = departmentKey(raw.department());
            UUID departmentId = key.isEmpty() ? null : departmentIds.get(key);
            prepared.add(new PreparedRow(raw, name, blankToNull(raw.department()), departmentId));
        }
        return prepared;
    }

    private ProcedureUploadRow evaluateRow(PreparedRow prepared, UUID uploadId,
                                           Map<String, Procedure> existing, Map<String, Integer> firstRowByKey) {
        ProcedureUploadRow row = baseRow(prepared, uploadId);
        CleanedName name = prepared.name();
        int excelRow = prepared.raw().excelRowNumber();

        if (name.isBlank()) {
            return result(row, ProcedureRowStatus.FAILED, ProcedureUploadErrorCode.NAME_REQUIRED,
                    "Row " + excelRow + ": Procedure name is required.");
        }
        if (name.display().length() > properties.getMaxNameLength()) {
            return result(row, ProcedureRowStatus.FAILED, ProcedureUploadErrorCode.NAME_TOO_LONG,
                    "Row " + excelRow + ": Procedure name exceeds " + properties.getMaxNameLength() + " characters.");
        }
        if (departmentKey(prepared.raw().department()).isEmpty()) {
            return result(row, ProcedureRowStatus.FAILED, ProcedureUploadErrorCode.DEPARTMENT_REQUIRED,
                    "Row " + excelRow + ": Department is required.");
        }
        if (prepared.departmentId() == null) {
            return result(row, ProcedureRowStatus.FAILED, ProcedureUploadErrorCode.DEPARTMENT_NOT_FOUND,
                    "Row " + excelRow + ": Department \"" + prepared.departmentRaw() + "\" not found.");
        }

        String key = duplicateKey(prepared.departmentId(), name.normalized());
        Integer firstRow = firstRowByKey.get(key);
        if (firstRow != null) {
            return result(row, ProcedureRowStatus.FAILED, ProcedureUploadErrorCode.DUPLICATE_IN_FILE,
                    "Row " + excelRow + ": \"" + name.display()
                            + "\" is duplicated in this file for this department. It first appeared in row "
                            + firstRow + ".");
        }
        firstRowByKey.put(key, excelRow);

        Procedure match = existing.get(key);
        if (match != null && match.isActive()) {
            return result(row, ProcedureRowStatus.SKIPPED, ProcedureUploadErrorCode.ALREADY_EXISTS,
                    "Row " + excelRow + ": \"" + name.display() + "\" already exists in this department.");
        }
        if (match != null) {
            return result(row, ProcedureRowStatus.FAILED, ProcedureUploadErrorCode.INACTIVE_EXISTS,
                    "Row " + excelRow + ": An inactive \"" + name.display()
                            + "\" procedure exists in this department. Reactivate it instead.");
        }
        return result(row, ProcedureRowStatus.VALID, null, null);
    }

    private ProcedureUploadRow baseRow(PreparedRow prepared, UUID uploadId) {
        ProcedureUploadRow row = new ProcedureUploadRow();
        row.setUploadId(uploadId);
        row.setExcelRowNumber(prepared.raw().excelRowNumber());
        row.setProcedureName(prepared.name().display());
        row.setDepartment(prepared.departmentRaw());
        row.setDescription(blankToNull(prepared.raw().description()));
        row.setDepartmentPublicId(prepared.departmentId());
        return row;
    }

    private void applyValidationCounts(ProcedureUpload upload, int totalRows, List<ProcedureUploadRow> rows) {
        upload.setTotalRows(totalRows);
        upload.setValidRows((int) count(rows, ProcedureRowStatus.VALID));
        upload.setSkippedRows((int) count(rows, ProcedureRowStatus.SKIPPED));
        upload.setFailedRows((int) count(rows, ProcedureRowStatus.FAILED));
        upload.setStatus(upload.getValidRows() > 0
                ? ProcedureUploadStatus.READY_FOR_IMPORT : ProcedureUploadStatus.VALIDATION_FAILED);
    }

    // --- import ---

    private void guardImportEligible(ProcedureUpload upload) {
        switch (upload.getStatus()) {
            case READY_FOR_IMPORT -> { /* eligible */ }
            case PROCESSING -> throw new IllegalStateException(
                    "Upload " + upload.getId() + " is already being processed");
            case COMPLETED, COMPLETED_WITH_ERRORS -> throw new IllegalStateException(
                    "Upload " + upload.getId() + " has already been imported");
            default -> throw new IllegalStateException("Upload " + upload.getId()
                    + " is not ready for import (status: " + upload.getStatus() + ")");
        }
    }

    private List<ProcedureUploadRow> recheckDuplicates(List<ProcedureUploadRow> candidates) {
        Map<UUID, Set<String>> byDepartment = new HashMap<>();
        for (ProcedureUploadRow row : candidates) {
            String normalized = normalizedNameOf(row);
            if (row.getDepartmentPublicId() != null && normalized != null) {
                byDepartment.computeIfAbsent(row.getDepartmentPublicId(), key -> new HashSet<>())
                        .add(normalized);
            }
        }
        Map<String, Procedure> existing = loadExisting(byDepartment);

        List<ProcedureUploadRow> toCreate = new ArrayList<>();
        for (ProcedureUploadRow row : candidates) {
            Procedure match = existing.get(duplicateKey(row.getDepartmentPublicId(), normalizedNameOf(row)));
            if (match != null && match.isActive()) {
                result(row, ProcedureRowStatus.SKIPPED, ProcedureUploadErrorCode.ALREADY_EXISTS,
                        "Row " + row.getExcelRowNumber() + ": \"" + row.getProcedureName()
                                + "\" already exists in this department.");
            } else if (match != null) {
                result(row, ProcedureRowStatus.FAILED, ProcedureUploadErrorCode.INACTIVE_EXISTS,
                        "Row " + row.getExcelRowNumber() + ": An inactive \"" + row.getProcedureName()
                                + "\" procedure exists in this department. Reactivate it instead.");
            } else {
                toCreate.add(row);
            }
        }
        return toCreate;
    }

    private void createInBatches(List<ProcedureUploadRow> toCreate, ProcedureUpload upload) {
        int batchSize = Math.max(1, properties.getBatchSize());
        for (int start = 0; start < toCreate.size(); start += batchSize) {
            List<ProcedureUploadRow> chunk = toCreate.subList(start, Math.min(start + batchSize, toCreate.size()));
            persistChunk(chunk, upload);
        }
    }

    private void persistChunk(List<ProcedureUploadRow> chunk, ProcedureUpload upload) {
        List<Procedure> procedures = chunk.stream().map(row -> procedureMapper.newProcedure(
                nameNormalizer.clean(row.getProcedureName()),
                blankToNull(row.getDescription()),
                row.getDepartmentPublicId(),
                codeGenerator.next(),
                upload.getId())).toList();
        try {
            List<Procedure> saved = procedureRepository.saveAll(procedures);
            procedureRepository.flush();
            for (int i = 0; i < chunk.size(); i++) {
                ProcedureUploadRow row = result(chunk.get(i), ProcedureRowStatus.CREATED, null, null);
                row.setProcedurePublicId(saved.get(i).getId());
            }
        } catch (DataIntegrityViolationException ex) {
            throw new IllegalStateException(
                    "A concurrent change introduced a duplicate procedure; please re-validate and import again.");
        }
    }

    private void applyImportCounts(ProcedureUpload upload, List<ProcedureUploadRow> rows) {
        upload.setCreatedRows((int) count(rows, ProcedureRowStatus.CREATED));
        upload.setSkippedRows((int) count(rows, ProcedureRowStatus.SKIPPED));
        upload.setFailedRows((int) count(rows, ProcedureRowStatus.FAILED));
        upload.setStatus(upload.getFailedRows() > 0 || upload.getSkippedRows() > 0
                ? ProcedureUploadStatus.COMPLETED_WITH_ERRORS : ProcedureUploadStatus.COMPLETED);
        upload.setCompletionTime(Instant.now());
    }

    // --- shared helpers ---

    /** Groups the eligible rows' normalized names by their resolved department id. */
    private Map<UUID, Set<String>> groupNormalizedByDepartment(List<PreparedRow> prepared) {
        Map<UUID, Set<String>> byDepartment = new HashMap<>();
        for (PreparedRow row : prepared) {
            if (row.departmentId() != null && eligibleName(row.name())) {
                byDepartment.computeIfAbsent(row.departmentId(), key -> new HashSet<>())
                        .add(row.name().normalized());
            }
        }
        return byDepartment;
    }

    /** One bulk query per department; keyed by department id + normalized name. */
    private Map<String, Procedure> loadExisting(Map<UUID, Set<String>> normalizedByDepartment) {
        Map<String, Procedure> existing = new HashMap<>();
        normalizedByDepartment.forEach((departmentId, names) -> {
            if (!names.isEmpty()) {
                procedureRepository.findByDepartmentPublicIdAndNormalizedNameIn(departmentId, names)
                        .forEach(procedure -> existing.put(
                                duplicateKey(departmentId, procedure.getNormalizedName()), procedure));
            }
        });
        return existing;
    }

    private String departmentKey(String department) {
        return department == null ? "" : department.trim().toLowerCase(Locale.ROOT);
    }

    private String duplicateKey(UUID departmentId, String normalizedName) {
        return departmentId + "|" + normalizedName;
    }

    /** Recomputes the normalized (duplicate-detection) form from the stored procedure name. */
    private String normalizedNameOf(ProcedureUploadRow row) {
        return row.getProcedureName() == null ? null
                : nameNormalizer.clean(row.getProcedureName()).normalized();
    }

    private boolean eligibleName(CleanedName name) {
        return !name.isBlank() && name.display().length() <= properties.getMaxNameLength();
    }

    private ProcedureUploadRow result(ProcedureUploadRow row, ProcedureRowStatus status,
                                      ProcedureUploadErrorCode code, String message) {
        row.setRowStatus(status);
        row.setErrorCode(code == null ? null : code.name());
        row.setErrorMessage(message);
        return row;
    }

    private long count(List<ProcedureUploadRow> rows, ProcedureRowStatus status) {
        return rows.stream().filter(row -> row.getRowStatus() == status).count();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private ProcedureUpload getUploadEntity(UUID uploadPublicId) {
        return uploadRepository.findById(uploadPublicId)
                .orElseThrow(() -> new ResourceNotFoundException("ProcedureUpload", uploadPublicId));
    }

    private record PreparedRow(ProcedureExcelRow raw, CleanedName name, String departmentRaw, UUID departmentId) {
    }
}
