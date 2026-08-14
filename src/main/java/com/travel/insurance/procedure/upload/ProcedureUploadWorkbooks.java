package com.travel.insurance.procedure.upload;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * Generates the lightweight upload template and the error report workbooks. The
 * template is static, so its bytes are built once and cached. Both use plain
 * headers and data rows — no styling, formulas, images or auto-sizing.
 */
@Component
public class ProcedureUploadWorkbooks {

    private static final String[] TEMPLATE_HEADERS = {"Procedure Name*", "Department*", "Description"};
    private static final String[] ERROR_REPORT_HEADERS =
            {"Excel Row", "Procedure Name", "Department", "Description", "Result", "Error Code", "Error Message"};

    private volatile byte[] cachedTemplate;

    public byte[] template() {
        byte[] template = cachedTemplate;
        if (template == null) {
            synchronized (this) {
                if (cachedTemplate == null) {
                    cachedTemplate = buildTemplate();
                }
                template = cachedTemplate;
            }
        }
        return template;
    }

    public byte[] errorReport(List<ProcedureUploadRow> rows) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Errors");
            writeHeader(sheet, ERROR_REPORT_HEADERS);
            int rowIndex = 1;
            for (ProcedureUploadRow row : rows) {
                Row dataRow = sheet.createRow(rowIndex++);
                dataRow.createCell(0).setCellValue(row.getExcelRowNumber());
                dataRow.createCell(1).setCellValue(nullSafe(row.getProcedureName()));
                dataRow.createCell(2).setCellValue(nullSafe(row.getDepartment()));
                dataRow.createCell(3).setCellValue(nullSafe(row.getDescription()));
                dataRow.createCell(4).setCellValue(row.getRowStatus() == null ? "" : row.getRowStatus().name());
                dataRow.createCell(5).setCellValue(nullSafe(row.getErrorCode()));
                dataRow.createCell(6).setCellValue(nullSafe(row.getErrorMessage()));
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to generate the error report", e);
        }
    }

    private byte[] buildTemplate() {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Procedures");
            writeHeader(sheet, TEMPLATE_HEADERS);
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to generate the upload template", e);
        }
    }

    private void writeHeader(Sheet sheet, String[] headers) {
        Row header = sheet.createRow(0);
        for (int c = 0; c < headers.length; c++) {
            header.createCell(c).setCellValue(headers[c]);
        }
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
