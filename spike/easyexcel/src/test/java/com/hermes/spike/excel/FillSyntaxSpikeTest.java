package com.hermes.spike.excel;

import cn.idev.excel.FastExcel;
import cn.idev.excel.ExcelWriter;
import cn.idev.excel.write.metadata.WriteSheet;
import cn.idev.excel.write.metadata.fill.FillConfig;
import cn.idev.excel.write.metadata.fill.FillWrapper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EasyExcel(以 FastExcel 执行)模板填充语法 spike。
 * 模板生成与产物读回都走原生 POI，消除 FastExcel 读/写隐式行为干扰。
 * 冻结结论见 docs/superpowers/specs/2026-09-28-easyexcel-fill-syntax.md。
 */
public class FillSyntaxSpikeTest {

    @TempDir
    Path tmp;

    // ---------- helpers ----------

    /** 用原生 POI 写模板：单元格直接放 {占位符} 字符串。 */
    private static void writeTemplate(File file, List<List<Object>> rows) {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Sheet1");
            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r);
                List<Object> vals = rows.get(r);
                for (int c = 0; c < vals.size(); c++) {
                    Cell cell = row.createCell(c);
                    cell.setCellValue(String.valueOf(vals.get(c)));
                }
            }
            try (FileOutputStream fos = new FileOutputStream(file)) {
                wb.write(fos);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static List<Object> cells(Object... values) {
        return List.of(values);
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /** 用原生 POI 读回所有单元格，返回行→单元格字符串。 */
    private static List<List<String>> readBack(File file) throws Exception {
        List<List<String>> result = new ArrayList<>();
        try (XSSFWorkbook wb = new XSSFWorkbook(file)) {
            DataFormatter fmt = new DataFormatter();
            Sheet sheet = wb.getSheetAt(0);
            for (Row row : sheet) {
                List<String> cells = new ArrayList<>();
                for (Cell c : row) {
                    cells.add(fmt.formatCellValue(c));
                }
                result.add(cells);
            }
        }
        return result;
    }

    // ---------- #1 单列表 {.字段}：表头应保留、列表向下展开 ----------

    @Test
    void test1_singleListFill() throws Exception {
        File tpl = tmp.resolve("single.xlsx").toFile();
        writeTemplate(tpl, List.of(
                cells("name", "number"),
                cells("{.name}", "{.number}")));

        File out = tmp.resolve("single-out.xlsx").toFile();
        FastExcel.write(out).withTemplate(tpl).sheet().doFill(List.of(
                map("name", "张三", "number", "1"),
                map("name", "李四", "number", "2"),
                map("name", "王五", "number", "3")));

        List<List<String>> rows = readBack(out);
        assertEquals(4, rows.size(), "表头 + 3 数据行");
        assertEquals("name", rows.get(0).get(0));
        assertEquals("张三", rows.get(1).get(0));
        assertEquals("2", rows.get(2).get(1));
        assertEquals("王五", rows.get(3).get(0));
    }

    // ---------- #2 多列表同 sheet：{别名.字段} + FillWrapper + forceNewRow ----------

    @Test
    void test2_multiListSameSheet() throws Exception {
        File tpl = tmp.resolve("multi.xlsx").toFile();
        writeTemplate(tpl, List.of(
                cells("{a.name}", "{a.number}"),
                cells("{b.name}", "{b.number}")));

        File out = tmp.resolve("multi-out.xlsx").toFile();
        try (ExcelWriter writer = FastExcel.write(out).withTemplate(tpl).build()) {
            WriteSheet sheet = FastExcel.writerSheet().build();
            FillConfig forceNewRow = FillConfig.builder().forceNewRow(true).build();
            writer.fill(new FillWrapper("a", List.of(
                    map("name", "A1", "number", "1"),
                    map("name", "A2", "number", "2"))), forceNewRow, sheet);
            writer.fill(new FillWrapper("b", List.of(
                    map("name", "B1", "number", "10"),
                    map("name", "B2", "number", "20"))), forceNewRow, sheet);
        }

        List<List<String>> rows = readBack(out);
        assertEquals(4, rows.size());
        assertEquals("A1", rows.get(0).get(0));
        assertEquals("A2", rows.get(1).get(0));
        assertEquals("B1", rows.get(2).get(0));
        assertEquals("B2", rows.get(3).get(0));
    }

    // ---------- #3a 单值：非点号 {字段} 标量 map-fill(推荐写法) ----------

    @Test
    void test3a_nondottedScalarFills() throws Exception {
        File tpl = tmp.resolve("scalar1.xlsx").toFile();
        writeTemplate(tpl, List.of(
                cells("{title}", "{value}")));

        File out = tmp.resolve("scalar1-out.xlsx").toFile();
        FastExcel.write(out).withTemplate(tpl).sheet().doFill(map("title", "昨日汇总", "value", "42"));

        List<List<String>> rows = readBack(out);
        assertEquals("昨日汇总", rows.get(0).get(0));
        assertEquals("42", rows.get(0).get(1));
    }

    // ---------- #3b 单值：点号 {数据集.字段} 与列表别名冲突(记录结论) ----------

    @Test
    void test3b_dottedScalarDoesNotResolve() throws Exception {
        File tpl = tmp.resolve("scalar2.xlsx").toFile();
        writeTemplate(tpl, List.of(
                cells("{report.title}")));

        File out = tmp.resolve("scalar2-out.xlsx").toFile();
        FastExcel.write(out).withTemplate(tpl).sheet().doFill(map("report.title", "日报"));

        List<List<String>> rows = readBack(out);
        System.out.println("[spike-#3b] 点号单值 map-fill 产物原始内容=" + rows);
        boolean resolved = !rows.isEmpty() && !rows.get(0).isEmpty()
                && "日报".equals(rows.get(0).get(0));
        assertFalse(resolved, "点号单值不应被当作标量解析(与列表别名语义冲突)");
    }

    // ---------- #4 5 万行 × 20 列分批填充(性能) ----------

    @Test
    void test4_batchFillPerf() {
        int cols = 20;
        List<Object> header = new ArrayList<>();
        List<Object> placeholder = new ArrayList<>();
        for (int c = 0; c < cols; c++) {
            header.add("c" + c);
            placeholder.add("{.c" + c + "}");
        }

        File tpl = tmp.resolve("wide.xlsx").toFile();
        writeTemplate(tpl, List.of(header, placeholder));

        File out = tmp.resolve("wide-out.xlsx").toFile();
        int total = 50_000, batch = 5_000;

        long beforeHeap = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        long t0 = System.nanoTime();

        try (ExcelWriter writer = FastExcel.write(out).withTemplate(tpl).build()) {
            WriteSheet sheet = FastExcel.writerSheet().build();
            for (int start = 0; start < total; start += batch) {
                List<Map<String, Object>> rows = new ArrayList<>(batch);
                for (int i = 0; i < batch; i++) {
                    Map<String, Object> m = new HashMap<>();
                    int base = start + i;
                    for (int c = 0; c < cols; c++) {
                        m.put("c" + c, base + c);
                    }
                    rows.add(m);
                }
                writer.fill(rows, sheet);
            }
        }

        long costMs = (System.nanoTime() - t0) / 1_000_000;
        long heapDelta = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) - beforeHeap;
        System.out.printf("[spike-#4] 5万行×20列 分批填充 耗时=%dms 堆增量≈%dMB%n",
                costMs, heapDelta / 1024 / 1024);

        assertTrue(costMs <= 30_000, "4 核口径应为 ≤30s，实际 " + costMs + "ms");
    }

    // ---------- #5 产物正确性：回读断言关键拐点 ----------

    @Test
    void test5_readBackCorrectness() throws Exception {
        File tpl = tmp.resolve("correct.xlsx").toFile();
        writeTemplate(tpl, List.of(
                cells("id", "name", "number"),
                cells("{.id}", "{.name}", "{.number}")));

        File out = tmp.resolve("correct-out.xlsx").toFile();
        List<Map<String, Object>> data = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            data.add(map("id", String.valueOf(i), "name", "N" + i, "number", String.valueOf(i * 0.5)));
        }
        FastExcel.write(out).withTemplate(tpl).sheet().doFill(data);

        List<List<String>> rows = readBack(out);
        assertEquals(101, rows.size(), "表头 + 100 行");
        assertEquals("id", rows.get(0).get(0));
        assertEquals("N1", rows.get(1).get(1));
        assertEquals("N50", rows.get(50).get(1));
        assertEquals("N100", rows.get(100).get(1));
        assertEquals("50.0", rows.get(100).get(2));
    }
}