package com.claimpilot.document.adapter;

import com.claimpilot.document.application.PdfRenderer;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.util.List;

@Component
class OpenPdfRenderer implements PdfRenderer {

    @Override
    public byte[] render(String title, List<String[]> fields, List<String> tableHeader, List<List<String>> table,
                         String footer) {
        var out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 48, 48, 48, 48);
        PdfWriter.getInstance(doc, out);
        doc.open();
        Font h = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16);
        Font b = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10);
        Font n = FontFactory.getFont(FontFactory.HELVETICA, 10);
        doc.add(new Paragraph(title, h));
        doc.add(new Paragraph(" "));
        PdfPTable kv = new PdfPTable(new float[] {1, 2});
        kv.setWidthPercentage(100);
        for (String[] f : fields) {
            kv.addCell(cell(f[0], b));
            kv.addCell(cell(f[1], n));
        }
        doc.add(kv);
        if (!tableHeader.isEmpty()) {
            doc.add(new Paragraph(" "));
            PdfPTable t = new PdfPTable(tableHeader.size());
            t.setWidthPercentage(100);
            tableHeader.forEach(c -> t.addCell(cell(c, b)));
            table.forEach(row -> row.forEach(c -> t.addCell(cell(c, n))));
            doc.add(t);
        }
        if (footer != null) {
            doc.add(new Paragraph(" "));
            Paragraph p = new Paragraph(footer, FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 8));
            p.setAlignment(Element.ALIGN_LEFT);
            doc.add(p);
        }
        doc.close();
        return out.toByteArray();
    }

    private static PdfPCell cell(String text, Font f) {
        PdfPCell c = new PdfPCell(new Phrase(text == null ? "" : text, f));
        c.setPadding(4);
        return c;
    }
}
