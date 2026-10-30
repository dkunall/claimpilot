package com.claimpilot.document.application;

import java.util.List;

/** Port for printable documents. Takes plain rows so the renderer knows nothing about policies or claims. */
public interface PdfRenderer {

    byte[] render(String title, List<String[]> fields, List<String> tableHeader, List<List<String>> table,
                  String footer);
}
