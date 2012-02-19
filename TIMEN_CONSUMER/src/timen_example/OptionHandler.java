/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package timen_example;

import FeatureBuilder.*;
import TIMEN.TIMEN;
import TimeML_BasicKit.TML_file_utils;
import java.io.*;
import java.text.*;
import java.util.*;
import nlp_files.*;
import utils_bk.*;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** @author Hector Llorens */
public class OptionHandler {

    public static enum Action {

        NORMALIZE_EXPR,
        NORMALIZE_TML,
        CREATE_KNOWLEDGE_FILE,
        OBTAIN_INPUT_FROM_TML;
    }
    private static TIMEN timen;
    private static SimpleDateFormat dct_format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm");

    /**
     * Obtain the value of one parameter of ap paramenters
     * @param params    the params string
     * @param param     the parameter we need the value from
     * @return          the value of the parameter param
     */
    public static String getParameter(String params, String param) {
        String paramValue = null;
        if (params != null && params.contains(param)) {
            if (params.matches(".*" + param + "=[^,]*,.*")) {
                paramValue = params.substring(params.lastIndexOf(param + "=") + param.length() + 1, params.indexOf(',', params.lastIndexOf(param + "=")));
            } else {
                if (params.matches(".*" + param + "=[^,]*")) {
                    paramValue = params.substring(params.lastIndexOf(param + "=") + param.length() + 1);
                }
            }
        }
        return paramValue;
    }

    public static void doAction(String action, String[] input, String action_parameters, String lang) {

        try {
            System.err.println("\n\nDoing action: " + action.toUpperCase() + "\n------------");
            switch (Action.valueOf(action.toUpperCase())) {


                /* This is a non-NLP baseline, the only context info is dct */
                case NORMALIZE_EXPR: {
                    /* Set DCT */
                    String dctvalue = getParameter(action_parameters, "dct");
                    if (dctvalue == null) {
                        dctvalue = dct_format.format(new Date());
                    }
                    /* Create a timen object */
                    timen = new TIMEN(new Locale(lang));

                    /* Process expression */
                    if (input.length == 1) {
                        timen.normalize(input[0], dctvalue);
                    } else {
                        System.err.println("Expecting one expression, found " + input.length);
                    }
                }
                break;

                case NORMALIZE_TML: {
                    /* DCT is got from the tml file */

                    /* Create a timen object */
                    timen = new TIMEN(new Locale(lang));

                    /* Process files */
                    for (int i = 0; i < input.length; i++) {
                        File input_file = new File(input[i]);
                        if (!input_file.exists()) {
                            throw new FileNotFoundException("File does not exist: " + input_file);
                        }
                        if (!input_file.isFile()) {
                            throw new IllegalArgumentException("File must be a regular file: " + input_file);
                        }

                        File outputfile = getNLPfeatures(input_file, lang);

                        /* get normalized values -- ids need to be specified*/
                        HashMap<String, String> normalization = contextaware_normalization(outputfile);

                        /* RESTORE TML WITH IDs */
                        create_updated_tml_with_normalization(input_file, normalization);

                    }

                }
                break;

                case CREATE_KNOWLEDGE_FILE: {
                    /*
                     * This creates a knowledge_XX.java file from SQLite knowledge dbs where XX is the language code
                     */
                }
                break;
            }

        } catch (Exception e) {
            System.err.println("\nErrors found (ActionHandler):\n\t" + e.toString() + "\n");
            if (System.getProperty("DEBUG") != null && System.getProperty("DEBUG").equalsIgnoreCase("true")) {
                e.printStackTrace(System.err);
                System.exit(1);
            }
            //throw new RuntimeException("\tAction: " + action.toUpperCase());
        }

    }

    /*
     * Get all the NLP features required and asociate them to the id. We aim to minimize these.
     *
     * Currently these are limited to TENSE-ASPECT
     *
     */
    public static File getNLPfeatures(File input_file, String lang) {
        File outputfile = null;
        try {
            XMLFile xmlfile = new XMLFile();
            xmlfile.loadFile(input_file);
            xmlfile.setLanguage(lang);
            if (!xmlfile.getExtension().equalsIgnoreCase("tml")) {
                throw new Exception("TimeML (.tml) XML file is required as input.");
            }
            if (!xmlfile.isWellFormed()) {
                throw new Exception("File: " + xmlfile.getFile() + " is not a valid TimeML (.tml) XML file.");
            }

            // Create a working directory
            File dir = new File(input_file.getCanonicalPath() + "-TIPSemB-dataset/");
            if (!dir.exists() || !dir.isDirectory()) {
                dir.mkdir();
            }

            // Copy the valid TML-XML file
            String output = dir + "/" + input_file.getName();
            FileUtils.copyFileUtil(input_file, new File(output));
            xmlfile.loadFile(new File(output));


            // get plain
            String plainfile = xmlfile.toPlain();

            String features = null;
            features = BaseTokenFeatures.getFeatures4Plain(lang, plainfile, 1, false, "TempEval2-features", "TIPSemB");
            TML_file_utils.tml2dataset4model(xmlfile, features);

            // add TempEval2 features
            output = TempEvalFiles.merge_extents(dir.getCanonicalPath() + "/" + input_file.getName() + ".plain.TempEval2-features", dir + "/timex-extents.tab", "timex");
            features = TempEvalFiles.merge_attribs(output, dir + "/timex-attributes.tab", "timex");
            output = Timen.get_timen(features, lang);

            outputfile = new File(FileUtils.getFolder(input_file.getCanonicalPath()) + "/" + input_file.getName() + ".TIMEN_complete");
            FileUtils.copyFileUtil(new File(output), outputfile);
            //FileUtils.deleteRecursively(dir);
        } catch (Exception e) {
            System.err.println("\nErrors found (ActionHandler):\n\t" + e.toString() + "\n");
            if (System.getProperty("DEBUG") != null && System.getProperty("DEBUG").equalsIgnoreCase("true")) {
                e.printStackTrace(System.err);
                System.exit(1);
            }
        }
        return outputfile;
    }

    /**
     *
     * Get a normalized values for each timex with the ISO8601 value in a Hash id -> normalized value
     *
     * De entrada tb podria ser un hash...
     * text|tense|DCT|ref-val
     *
     *
     * text:		timex tokens separated by "_"
     * tense:           timex governing-verb tense
     * DCT:		Document Creation Time in ISO8601 format
     * DEPRECATED - ref-val:  Previous date/time-timex focus (temporal reference) (i.e., reference point time-location)
     *                          NOW IS HANDLED BY THE CONSUMER
     *
     * @param expr
     * @param output_file
     *
     */
    public static HashMap<String, String> contextaware_normalization(File input_file) {
        HashMap<String, String> normalization = null;
        try {
            normalization = new HashMap<String, String>();
            int linen = 0;
            BufferedReader pipesreader = new BufferedReader(new FileReader(input_file));
            String pipesline = null;
            String[] pipesarr = null;
            String ref_val = "0000-00-00";
            try {
                while ((pipesline = pipesreader.readLine()) != null) {
                    pipesarr = pipesline.split("\\|");
                    linen++;
                    if (pipesarr.length >= 2) {
                        String id = pipesarr[0];
                        String text = pipesarr[1];
                        String tense = pipesarr[2];
                        String dct = pipesarr[3];
                        //String ref_val=pipesarr[9];
                        if (ref_val.equals("0000-00-00")) {
                            ref_val = dct;
                        }
                        String norm_value = timen.normalize(text, dct, tense, ref_val);
                        //outfile.write(pipesline + "|" + norm_value + "\n");
                        normalization.put(id, norm_value);
                    }
                }
            } finally {
                if (pipesreader != null) {
                    pipesreader.close();
                }
            }

        } catch (Exception e) {
            System.err.println("\nErrors found (ActionHandler):\n\t" + e.toString() + "\n");
            if (System.getProperty("DEBUG") != null && System.getProperty("DEBUG").equalsIgnoreCase("true")) {
                e.printStackTrace(System.err);
                System.exit(1);
            }
        }
        return normalization;
    }

    public static void create_updated_tml_with_normalization(File file, HashMap<String, String> normalization) {
        try {
            String tmp = FileUtils.readFileAsString(file.getCanonicalPath(), "UTF-8");
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(file);
            doc.getDocumentElement().normalize();
            NodeList text = doc.getElementsByTagName("TEXT");
            if (text.getLength() > 1) {
                throw new Exception("More than one TEXT tag found.");
            }
            Element TextElmnt = (Element) text.item(0); // If not ELEMENT NODE will throw exception

            // normalize timexes
            NodeList current_node = TextElmnt.getElementsByTagName("TIMEX3");
            for (int s = 0; s < current_node.getLength(); s++) {
                Element element = (Element) current_node.item(s);
                if (normalization.get(element.getAttribute("tid")) != null) {
                    // remove value if found
                    tmp = tmp.replaceAll("(<TIMEX3[^>]*) value=\"[^\"]*\"([^>]*tid=\"" + element.getAttribute("tid") + "\"[^>]*>)", "$1" + "$2");
                    tmp = tmp.replaceAll("(<TIMEX3[^>]*tid=\"" + element.getAttribute("tid") + "\"[^>]*) value=\"[^\"]*\"([^>]*>)", "$1" + "$2");
                    // add new normalized value
                    tmp = tmp.replaceAll("(<TIMEX3[^>]*tid=\"" + element.getAttribute("tid") + "\"[^>]*)>", "$1 value=\""+ normalization.get(element.getAttribute("tid"))+"\">");
                }
            }
            doc=null;
            db=null;
            dbf=null;
            FileUtils.writeFileFromString(tmp, file.getCanonicalPath() + ".timen");
        } catch (Exception e) {
            System.err.println("\nErrors found (ActionHandler):\n\t" + e.toString() + "\n");
            if (System.getProperty("DEBUG") != null && System.getProperty("DEBUG").equalsIgnoreCase("true")) {
                e.printStackTrace(System.err);
                System.exit(1);
            }
        }
    }
}


