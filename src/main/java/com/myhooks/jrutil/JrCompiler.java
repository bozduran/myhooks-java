package com.myhooks.jrutil;

import java.io.ByteArrayInputStream;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperReport;
import net.sf.jasperreports.engine.design.JasperDesign;

/**
 * Thin wrapper over {@link JasperCompileManager} — the final compile gate used
 * by the validate step.
 */
public final class JrCompiler {

    private JrCompiler() {
    }

    public static JasperReport compile(JasperDesign design) throws JRException {
        return JasperCompileManager.compileReport(design);
    }

    public static JasperReport compile(byte[] data) throws JRException {
        return JasperCompileManager.compileReport(new ByteArrayInputStream(data));
    }
}
