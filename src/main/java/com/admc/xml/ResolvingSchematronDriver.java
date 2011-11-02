/*
 * @(#)$Id: ResolvingSchematronDriver.java 1986 2009-01-07 04:36:50Z blaine $
 *
 * Copyright 2008 Axis Data Management Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.admc.xml;


import com.sun.msv.driver.textui.DebugController;
import com.sun.msv.driver.textui.ReportErrorHandler;
import com.sun.msv.grammar.Grammar;
import com.sun.msv.schematron.reader.SRELAXNGReader;
import com.sun.msv.schematron.verifier.RelmesVerifier;
import com.sun.msv.verifier.regexp.REDocumentDeclaration;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.InputSource;
import javax.xml.parsers.SAXParserFactory;
import org.apache.xml.resolver.tools.CatalogResolver;
import java.io.IOException;
import java.net.URL;
import java.net.MalformedURLException;
import javax.xml.parsers.ParserConfigurationException;

/**
 * Extends Thread to facilitate Thread-specific resource settings by user.
 */
public class ResolvingSchematronDriver extends Thread {
    public static final int EXCEPTION_STATUS = 127;  // Highest shell var value

    /**
     * Exits with the final value of the exitStatus as set by the run() method.
     *
     * @see #run()
     */
	public static void main(final String[] sa) throws MalformedURLException {
        boolean isVerbose = sa.length > 0 && sa[0].equals("-v");
		if (sa.length < 1) {
            System.out.println(
                    "SYNTAX:  java -Xss512K "
                    + "-Dxml.catalog.files=path/to/catalog.xml "
                    + "-Dxml.catalog.X=Y... -jar path/to/admc-rs-msv-*.jar "
                    + "[-v] url.grammar url.xml...");
            System.out.println(
                    "If XML files use Xincludes, specify only the highest-"
                    + "level files and the");
            System.out.println("rest will be validated automatically.");
            System.out.println("Component files specified directly will fail "
                    + "validation if they have");
            System.out.println("unresolved id references.");
            System.out.println("All jar files listed in the admc-rs-msv "
                    + "MANIFEST class-path must reside");
            System.out.println("alongside the xcsde-rngval jar file itself.");
            return;
        }
		if (sa.length < (isVerbose ? 3 : 2))
			throw new IllegalArgumentException(
                    "Run with no arguments for syntax instructions");
        URL[] inputUrls = new URL[sa.length - (isVerbose ? 2 : 1)];
        for (int i = 0; i < inputUrls.length; i++)
            inputUrls[i] = ResolvingSchematronDriver
                    .defaultingUrl(sa[i + (isVerbose ? 2 : 1)]);
            ResolvingSchematronDriver driver =
                    new ResolvingSchematronDriver(ResolvingSchematronDriver
                    .defaultingUrl(sa[isVerbose ? 1 : 0]), inputUrls);
        driver.setVerbose(isVerbose);
        driver.start();
        try {
            driver.join();
        } catch (InterruptedException ie) {
            throw new RuntimeException(ie);
        }
        System.exit(driver.getExitStatus());
    }

    /**
     * Imperfect URL constructor that defaults to file type.
     */
    static protected URL defaultingUrl(String s) throws MalformedURLException {
        return new URL((s.indexOf(':') < 2) ? ("file:" + s) : s);
        // Allow colon in file paths only in drive designation position
    }

    private URL grammarUrl; private URL[] sourceUrls;
    private int exitStatus = 0;
    private boolean verbose = false;

    public int getExitStatus() {
        return exitStatus;
    }
    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    /**
     * @param grammarUrl  URL to the schema file to constrain the grammar
     * @param sourceUrls  Array of XML files to be validated
     */
    public ResolvingSchematronDriver(URL grammarUrl, URL[] sourceUrls) {
        this.grammarUrl = grammarUrl;
        this.sourceUrls = sourceUrls;
    }

    /**
     * Sets this object's exit status to 0 if all given files validated
     * successfully; or count of files which failed validation; or 127
     * if program execution aborted.
     */
    public void run() {
        if (verbose) System.out.println(
                "Validating " +  sourceUrls.length + " file(s) "
                + " with grammar: " + grammarUrl);
        Throwable cause;
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            Grammar grammar = SRELAXNGReader.parse(
                    new InputSource(grammarUrl.openStream()),
                    factory, new DebugController(false, false));
            if (grammar == null)
                throw new RuntimeException(
                        "Unexpected failure parsing grammar URL '"
                        + grammarUrl + "'");
		
            RelmesVerifier verifier;
            XMLReader reader;
            CatalogResolver catalogResolver = new CatalogResolver();
		
            for (int i = 0; i < sourceUrls.length; i++ ) {
                verifier = new RelmesVerifier(
                        new REDocumentDeclaration(grammar),
                        new ReportErrorHandler());
                reader = factory.newSAXParser().getXMLReader();
                reader.setEntityResolver(catalogResolver);
                reader.setContentHandler(verifier);
                if (verbose)
                    System.out.print("Validating " + sourceUrls[i] + "...  ");
                try {
                    reader.parse(new InputSource(sourceUrls[i].openStream()));
                } catch (SAXException e) {
                    if (verbose) System.out.println(); // See note below
                    cause = e.getCause();
                    System.err.println(
                            cause == null ? e.toString() : cause.toString());
                }
                if (verifier.isValid()) {
                    if (verbose) System.out.println("valid");
                } else {
                    exitStatus++;
                    System.err.println(sourceUrls[i] + " INVALID!");
                }
            }
        } catch (ParserConfigurationException e) {
            if (verbose) System.out.println();
            // Otherwise verbose output may get mangled with error message(s)
            cause = e.getCause();
            System.out.println(e.toString());
            exitStatus = EXCEPTION_STATUS;
            return;
        } catch (IOException e) {
            if (verbose) System.out.println(); // See note above
            cause = e.getCause();
            System.err.println(cause == null ? e.toString() : cause.toString());
            exitStatus = EXCEPTION_STATUS;
            return;
        } catch (Throwable t) {
            if (verbose) System.out.println(); // See note above
            System.err.println("Unexpected problem encountered");
            t.printStackTrace();
            exitStatus = EXCEPTION_STATUS;
            return;
        }
	}
}
