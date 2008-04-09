package com.rcc.bgen;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.DirectoryScanner;
import org.apache.tools.ant.Task;
import org.apache.tools.ant.types.FileSet;
import org.apache.tools.ant.types.Mapper;
import org.apache.tools.ant.types.Path;
import org.apache.tools.ant.types.Reference;
import org.apache.tools.ant.util.ClasspathUtils;
import org.apache.tools.ant.util.FileNameMapper;

import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.context.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BGenTask extends Task {
    public static class TemplateContext {
        private String name;
        private String fqcn;

        public void setName(String name) {
            this.name = name;
        }

        public String getName() {
            return this.name;
        }

        public void setClassname(String fqcn) {
            this.fqcn = fqcn;
        }

        public String getClassname() {
            return this.fqcn;
        }
    }

    private static final Pattern indexedPropertyPattern = Pattern.compile("^(\\w+)\\[(\\d*)\\].*");

    private File destdir;
    private File templateFile;
    private List<TemplateContext> templateContexts = new ArrayList<TemplateContext>();
    private ClasspathUtils.Delegate cpDelegate;
    private List<FileSet> filesets;
    private Mapper mapperElement;

    public void setDestDir(File destdir) {
        this.destdir = destdir;
    }

    public void setTemplateFile(File templateFile) {
        this.templateFile = templateFile;
    }

    public void setClasspathRef(Reference r) {
        this.cpDelegate.setClasspathref(r);
    }

    public void addFileset(FileSet fileset) {
        this.filesets.add(fileset);
    }

/*
    public Mapper createMapper() {
        this.mapperElement = new Mapper(this.getProject());
        return this.mapperElement;
    }
*/

    public void add(FileNameMapper fileNameMapper) {
        this.mapperElement = new Mapper(this.getProject());
        this.mapperElement.add(fileNameMapper);
    }

    public Path createClasspath() {
        return this.cpDelegate.createClasspath();
    }

    public TemplateContext createTemplateContext() {
        TemplateContext tc = new TemplateContext();
        this.templateContexts.add(tc);
        return tc;
    }

    public void init() {
        this.templateContexts = new ArrayList<TemplateContext>();
        this.filesets = new ArrayList<FileSet>();
        this.cpDelegate = ClasspathUtils.getDelegate(this);
        super.init();
    }

    public void execute() throws BuildException {
        // First, check if the task has been configured correctly
        if (this.destdir == null) {
            throw new BuildException("destdir must be specified");
        }

        if (this.templateFile == null) {
            throw new BuildException("templateFile must be specified");
        }

        if (!this.templateFile.exists()) {
            throw new BuildException("templateFile must exist: " + this.templateFile);
        }

        if (!this.destdir.isDirectory()) {
            throw new BuildException("destdir must be an existing directory: " + this.destdir);
        }

        try {
            VelocityEngine engine = new VelocityEngine();
            engine.init();
            Map<String, Object> model = new HashMap<String, Object>();

            for (TemplateContext tc : this.templateContexts) {
                this.cpDelegate.setClassname(tc.getClassname());
                model.put(tc.getName(), this.cpDelegate.newInstance());
            }
            Context ctx = new VelocityContext(model);

            FileNameMapper mapper = this.mapperElement.getImplementation();

            for (FileSet fileset : this.filesets) {
                DirectoryScanner ds = fileset.getDirectoryScanner();
                ds.scan();
                for (String filename : ds.getIncludedFiles()) {
                    File f = new File(fileset.getDir(), filename);

                    String[] targetFileNames = mapper.mapFileName(filename);
                    if (targetFileNames == null || targetFileNames.length == 0) {
                        throw new BuildException("Target file name could not be mapped: "
                                + filename);
                    }
                    if (targetFileNames.length > 1) {
                        throw new BuildException("Multiple target file names were mapped: "
                                + filename);
                    }
                    String destFilename = targetFileNames[0];

                    File destFile = new File(this.destdir, destFilename);
                    if (destFile.exists()) {
                        if (f.lastModified() < destFile.lastModified()
                                && this.templateFile.lastModified() < destFile.lastModified())
                        {
                            continue;
                        }
                    }

                    Properties props = this.getProperties(f);
                    _execute(props, destFile, engine, ctx);
                }
            }
        } catch (BuildException e) {
            throw e;
        } catch (Exception e) {
            log("Unexpected Exception: " + e.getMessage());
            e.printStackTrace();
            throw new BuildException(e);
        }
    }

    private Properties getProperties(File propsFile) throws IOException {
        InputStream is = new FileInputStream(propsFile);
        Properties props = new Properties();
        props.load(is);
        return props;
    }

    private void _execute(Properties props, File destFile, VelocityEngine engine, Context ctx)
        throws Exception
    {
        log(destFile.toString());
        Map bean = new HashMap();

        for (String name : props.stringPropertyNames()) {
            String value = props.getProperty(name);
            if (name.startsWith("bgen.meta.")) {
                continue;
            }

            populate(bean, name, value);
        }

        ctx.put("bean", bean);

        Reader reader = null;
        Writer writer = null;
        try {
            reader = new FileReader(this.templateFile);
            writer = new FileWriter(destFile);

            boolean success = engine.evaluate(ctx, writer, "velocity", reader);

            if (!success) {
                throw new BuildException("Evaluation of template failed for " + destFile);
            }
        } finally {
            if (reader != null) { reader.close(); }
            if (writer != null) { writer.close(); }
        }
    }

    private void populate(Map bean, String path, Object value) {
        Matcher matcher = indexedPropertyPattern.matcher(path);

        if (path.indexOf('.') == -1) { // This should be a simple property

            if (matcher.matches()) { // indexed property
                String partName = matcher.group(1);
                String idxStr = matcher.group(2);

                int idx = -1;
                if (!idxStr.isEmpty()) {
                    idx = Integer.parseInt(idxStr);
                }

                // Create the list if it does not exist
                if (!bean.containsKey(partName)) {
                    bean.put(partName, new ArrayList());
                }

                List list = ((List) bean.get(partName));

                if (idx >= 0) {
                    while (list.size() <= idx) {
                        list.add(null);
                    }
                    list.set(idx, value);
                } else {
                    list.add(value);
                }
            } else { // simple property
                bean.put(path, value);
            }

        } else {

            String part = path.substring(0, path.indexOf('.'));
            String rest = path.substring(path.indexOf('.') + 1, path.length());

            if (matcher.matches()) { // indexed property
                String partName = matcher.group(1);
                String idxStr = matcher.group(2);

                int idx = -1;
                if (!idxStr.isEmpty()) {
                    idx = Integer.parseInt(idxStr);
                }

                // Create the list if it does not exist
                if (!bean.containsKey(partName)) {
                    bean.put(partName, new ArrayList());
                }

                List list = ((List) bean.get(partName));

                Map subBean = new HashMap();
                if (idx >= 0 && list.size() > idx && list.get(idx) != null) {
                    subBean = (Map) list.get(idx);
                }

                this.populate(subBean, rest, value);

                if (idx >= 0) {
                    while (list.size() <= idx) {
                        list.add(null);
                    }
                    list.set(idx, subBean);
                } else {
                    list.add(subBean);
                }
            } else { // mapped property
                Map subBean = null;
                if (bean.containsKey(part)) {
                    Object obj = bean.get(part);
                    if (obj instanceof Map) {
                        subBean = (Map) obj;
                    } else if (obj instanceof String) {
                        throw new BuildException(
                                "Type should be java.util.Map, not java.lang.String");
                    } else {
                        throw new BuildException("Unexpected type: " + obj.getClass());
                    }
                    this.populate(subBean, rest, value);
                } else {
                    subBean = new HashMap();
                    this.populate(subBean, rest, value);
                    bean.put(part, subBean);
                }
            }
        }
    }
}
