package com.travel.insurance.procedure.upload;

import com.travel.insurance.config.ProcedureUploadProperties;
import com.travel.insurance.department.DepartmentService;
import com.travel.insurance.procedure.Procedure;
import com.travel.insurance.procedure.ProcedureCodeGenerator;
import com.travel.insurance.procedure.ProcedureMapper;
import com.travel.insurance.procedure.ProcedureNameNormalizer;
import com.travel.insurance.procedure.ProcedureRepository;
import com.travel.insurance.procedure.upload.ProcedureExcelParser.ProcedureExcelRow;
import com.travel.insurance.procedure.upload.dto.ProcedureImportResponse;
import com.travel.insurance.procedure.upload.dto.ProcedureUploadRowResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProcedureUploadServiceImplTest {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Mock
    private DepartmentService departmentService;
    @Mock
    private ProcedureExcelParser excelParser;
    @Mock
    private ProcedureCodeGenerator codeGenerator;
    @Mock
    private ProcedureRepository procedureRepository;
    @Mock
    private ProcedureUploadRepository uploadRepository;
    @Mock
    private ProcedureUploadRowRepository rowRepository;

    private ProcedureUploadServiceImpl service;
    private UUID radiologyId;

    @BeforeEach
    void setUp() {
        service = new ProcedureUploadServiceImpl(
                departmentService, excelParser, new ProcedureNameNormalizer(), codeGenerator,
                new ProcedureMapper(), procedureRepository, uploadRepository, rowRepository,
                new ProcedureUploadMapper(), new ProcedureUploadWorkbooks(), new ProcedureUploadProperties());
        radiologyId = UUID.randomUUID();
    }

    private MockMultipartFile file() {
        return new MockMultipartFile("file", "procedures.xlsx", XLSX, new byte[]{1, 2, 3});
    }

    private Procedure existing(String normalized, boolean active, UUID departmentId) {
        Procedure procedure = new Procedure();
        procedure.setId(UUID.randomUUID());
        procedure.setName(normalized);
        procedure.setNormalizedName(normalized);
        procedure.setActive(active);
        procedure.setDepartmentPublicId(departmentId);
        procedure.setProcedureCode("PRC-0001");
        return procedure;
    }

    private ProcedureUploadRow row(int number, String name, UUID departmentId, ProcedureRowStatus status) {
        ProcedureUploadRow row = new ProcedureUploadRow();
        row.setExcelRowNumber(number);
        row.setProcedureName(name);
        row.setDepartmentPublicId(departmentId);
        row.setRowStatus(status);
        return row;
    }

    private ProcedureUploadRowResult rowResult(ProcedureImportResponse response, int excelRow) {
        return response.rows().stream()
                .filter(r -> r.excelRowNumber() == excelRow)
                .findFirst().orElseThrow();
    }

    /** Stubs the persistence round-trip so uploads that reach the create step succeed. */
    private void stubPersistence() {
        when(uploadRepository.saveAndFlush(any())).thenAnswer(inv -> {
            ProcedureUpload upload = inv.getArgument(0);
            upload.setId(UUID.randomUUID());
            return upload;
        });
        when(rowRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(uploadRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void uploadResolvesDepartmentsPerRowCreatesValidRowsAndClassifiesTheRest() {
        when(excelParser.parse(any())).thenReturn(List.of(
                new ProcedureExcelRow(2, "Nebulization", "Radiology", "Aerosol"),
                new ProcedureExcelRow(3, "NEBULIZATION", "radiology", ""),
                new ProcedureExcelRow(4, "", "Radiology", "description only"),
                new ProcedureExcelRow(5, "Chest Tube Insertion", "Radiology", ""),
                new ProcedureExcelRow(6, "Lumbar Puncture", "Cardiology", ""),
                new ProcedureExcelRow(7, "X-Ray", "", "chest")));
        when(departmentService.idsByName(anySet())).thenReturn(Map.of("radiology", radiologyId));
        when(procedureRepository.findByDepartmentPublicIdAndNormalizedNameIn(eq(radiologyId), anySet()))
                .thenReturn(List.of(existing("CHEST TUBE INSERTION", true, radiologyId)));
        when(codeGenerator.next()).thenReturn("PRC-0002");
        when(procedureRepository.saveAll(any())).thenAnswer(inv -> {
            List<Procedure> procedures = inv.getArgument(0);
            procedures.forEach(procedure -> procedure.setId(UUID.randomUUID()));
            return procedures;
        });
        stubPersistence();

        ProcedureImportResponse response = service.upload(file());

        assertThat(response.totalRows()).isEqualTo(6);
        assertThat(response.createdRows()).isEqualTo(1);
        assertThat(response.skippedRows()).isEqualTo(1);
        assertThat(response.failedRows()).isEqualTo(4);
        assertThat(response.status()).isEqualTo(ProcedureUploadStatus.COMPLETED_WITH_ERRORS);

        assertThat(rowResult(response, 2).status()).isEqualTo(ProcedureRowStatus.CREATED);
        assertThat(rowResult(response, 2).name()).isEqualTo("Nebulization");
        assertThat(rowResult(response, 2).createdProcedurePublicId()).isNotNull();
        assertThat(rowResult(response, 3).errorCode()).isEqualTo(ProcedureUploadErrorCode.DUPLICATE_IN_FILE.name());
        assertThat(rowResult(response, 3).errorMessage()).contains("first appeared in row 2");
        assertThat(rowResult(response, 4).errorCode()).isEqualTo(ProcedureUploadErrorCode.NAME_REQUIRED.name());
        assertThat(rowResult(response, 5).status()).isEqualTo(ProcedureRowStatus.SKIPPED);
        assertThat(rowResult(response, 6).errorCode()).isEqualTo(ProcedureUploadErrorCode.DEPARTMENT_NOT_FOUND.name());
        assertThat(rowResult(response, 6).errorMessage()).contains("Cardiology");
        assertThat(rowResult(response, 7).errorCode()).isEqualTo(ProcedureUploadErrorCode.DEPARTMENT_REQUIRED.name());
        verify(procedureRepository).saveAll(any());
    }

    @Test
    void uploadTreatsSameNameInDifferentDepartmentsAsDistinct() {
        UUID cardiologyId = UUID.randomUUID();
        when(excelParser.parse(any())).thenReturn(List.of(
                new ProcedureExcelRow(2, "Nebulization", "Radiology", ""),
                new ProcedureExcelRow(3, "Nebulization", "Cardiology", "")));
        when(departmentService.idsByName(anySet()))
                .thenReturn(Map.of("radiology", radiologyId, "cardiology", cardiologyId));
        when(procedureRepository.findByDepartmentPublicIdAndNormalizedNameIn(any(), anySet()))
                .thenReturn(List.of());
        when(codeGenerator.next()).thenReturn("PRC-0003");
        when(procedureRepository.saveAll(any())).thenAnswer(inv -> {
            List<Procedure> procedures = inv.getArgument(0);
            procedures.forEach(procedure -> procedure.setId(UUID.randomUUID()));
            return procedures;
        });
        stubPersistence();

        ProcedureImportResponse response = service.upload(file());

        assertThat(response.createdRows()).isEqualTo(2);
        assertThat(response.status()).isEqualTo(ProcedureUploadStatus.COMPLETED);
        assertThat(rowResult(response, 2).status()).isEqualTo(ProcedureRowStatus.CREATED);
        assertThat(rowResult(response, 3).status()).isEqualTo(ProcedureRowStatus.CREATED);
    }

    @Test
    void uploadRejectsEmptyFile() {
        MockMultipartFile empty = new MockMultipartFile("file", "procedures.xlsx", XLSX, new byte[0]);

        assertThatThrownBy(() -> service.upload(empty))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void uploadRejectsNonXlsxFile() {
        MockMultipartFile csv = new MockMultipartFile("file", "procedures.csv", "text/csv", new byte[]{1});

        assertThatThrownBy(() -> service.upload(csv))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(".xlsx");
    }

    @Test
    void templateReturnsWorkbookBytes() {
        assertThat(service.template()).isNotEmpty();
    }

    @Test
    void errorReportReturnsWorkbookBytes() {
        UUID uploadId = UUID.randomUUID();
        ProcedureUpload upload = new ProcedureUpload();
        upload.setId(uploadId);
        when(uploadRepository.findById(uploadId)).thenReturn(Optional.of(upload));
        when(rowRepository.findByUploadIdAndRowStatusInOrderByExcelRowNumberAsc(eq(uploadId), any()))
                .thenReturn(List.of(row(4, "", null, ProcedureRowStatus.FAILED)));

        assertThat(service.errorReport(uploadId)).isNotEmpty();
    }
}
