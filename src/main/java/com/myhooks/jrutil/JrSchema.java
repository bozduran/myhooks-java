package com.myhooks.jrutil;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.design.JasperDesign;
import net.sf.jasperreports.engine.xml.JRXmlLoader;

/**
 * Validates JRXML against the engine's schema by loading it through
 * {@link JRXmlLoader}, which rejects structurally-invalid-but-well-formed
 * documents. Returns the loaded {@link JasperDesign} for a subsequent compile.
 */
public final class JrSchema {

    private JrSchema() {
    }

    public static JasperDesign validate(byte[] data) throws JRException {
        return validate(new ByteArrayInputStream(data));
    }

    public static JasperDesign validate(InputStream in) throws JRException {
        try {
            return JRXmlLoader.load(in);
        } catch (JRException e) {
            throw e;
        } catch (RuntimeException e) {
            // JasperReports 7 parses JRXML with Jackson/Woodstox, which surfaces
            // malformed or structurally-invalid documents as runtime exceptions.
            throw new JRException(e.getMessage(), e);
        }
    }
}
