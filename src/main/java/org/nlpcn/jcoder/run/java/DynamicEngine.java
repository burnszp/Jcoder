package org.nlpcn.jcoder.run.java;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;

import org.apache.log4j.Logger;
import org.nlpcn.commons.lang.util.StringUtil;
import org.nlpcn.jcoder.controller.ThreadAction;
import org.nlpcn.jcoder.run.CodeException;
import org.nlpcn.jcoder.scheduler.TaskException;
import org.nlpcn.jcoder.service.TaskService;
import org.nlpcn.jcoder.util.StaticValue;

import com.google.common.base.Joiner;

@SuppressWarnings("all")
public class DynamicEngine {

	private static final Logger LOG = Logger.getLogger(DynamicEngine.class);

	private static DynamicEngine ourInstance;

	public static DynamicEngine getInstance() {
		return ourInstance;
	}

	/**
	 * 刷新instance
	 * @throws TaskException 
	 */
	public static void flush(URLClassLoader classLoader) throws TaskException {
		ourInstance = new DynamicEngine(classLoader);
		// 如果classloader被改变.全站的动态代码全部刷新一遍.
		synchronized (StaticValue.MAPPING) {
			StaticValue.getBean(TaskService.class, "taskService").initTaskFromDB();
		}

	}

	public static void close() throws IOException {
		ourInstance.parentClassLoader.close();
	}

	private URLClassLoader parentClassLoader;

	private String classpath;

	private DynamicEngine(URLClassLoader classLoader) {
		this.parentClassLoader = classLoader;
		this.buildClassPath();
	}

	private void buildClassPath() {
		this.classpath = null;
		Set<String> classPathSet = new HashSet<>();

		for (URL url : this.parentClassLoader.getURLs()) {
			try {
				String p = new File(url.toURI()).getAbsolutePath();
				classPathSet.add(p);
			} catch (URISyntaxException e) {
				e.printStackTrace();
			}
		}

		URLClassLoader classLoader = (URLClassLoader) this.getClass().getClassLoader();

		for (URL url : classLoader.getURLs()) {
			try {
				String p = new File(url.toURI()).getAbsolutePath();
				classPathSet.add(p);
			} catch (URISyntaxException e) {
				e.printStackTrace();
			}
		}

		this.classpath = Joiner.on(File.pathSeparator).join(classPathSet);
	}

	public Class<?> javaCodeToClass(String fullClassName, String javaCode) throws IOException, CodeException {
		Class<?> clazz = null;
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<JavaFileObject>();
		ClassFileManager fileManager = new ClassFileManager(compiler.getStandardFileManager(diagnostics, null, null));
		List<JavaFileObject> jfiles = new ArrayList<JavaFileObject>();
		jfiles.add(new CharSequenceJavaFileObject(fullClassName, javaCode));

		List<String> options = new ArrayList<String>();
		options.add("-encoding");
		options.add("UTF-8");
		options.add("-classpath");
		options.add(this.classpath);
		options.add("-parameters");

		JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, diagnostics, options, null, jfiles);
		boolean success = task.call();
		if (success) {
			JavaClassObject jco = fileManager.getMainJavaClassObject();
			DynamicClassLoader dynamicClassLoader = new DynamicClassLoader(this.parentClassLoader);
			try {
				List<JavaClassObject> innerClassJcos = fileManager.getInnerClassJavaClassObject();
				if (innerClassJcos != null && innerClassJcos.size() > 0) {
					for (JavaClassObject inner : innerClassJcos) {
						String name = inner.getName();
						name = name.substring(1, name.length() - 6).replace("/", ".");
						dynamicClassLoader.loadClass(name, inner);
					}
				}
				clazz = dynamicClassLoader.loadClass(fullClassName, jco);
			} catch (Exception e) {
				e.printStackTrace();
				LOG.error(e);
			} catch (Error e) {
				LOG.error(e);
				throw new CodeException(e.toString());
			} finally {
				if (dynamicClassLoader != null) {
					dynamicClassLoader.close();
				}
			}
		} else {
			StringBuilder error = new StringBuilder();
			for (Diagnostic<?> diagnostic : diagnostics.getDiagnostics()) {
				error.append(compilePrint(diagnostic));
			}
			throw new CodeException(error.toString());
		}

		return clazz;
	}
	
	
	public byte[] javaCode2Bytes(String fullClassName, String javaCode) throws IOException, CodeException {
		Class<?> clazz = null;
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<JavaFileObject>();
		ClassFileManager fileManager = new ClassFileManager(compiler.getStandardFileManager(diagnostics, null, null));
		List<JavaFileObject> jfiles = new ArrayList<JavaFileObject>();
		jfiles.add(new CharSequenceJavaFileObject(fullClassName, javaCode));

		List<String> options = new ArrayList<String>();
		
		options.add("-source");
		options.add("1.6");
		options.add("-target");
		options.add("1.6");
		options.add("-encoding");
		options.add("UTF-8");
		options.add("-classpath");
		options.add(this.classpath);
		options.add("-parameters");
		    

		JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, diagnostics, options, null, jfiles);
		boolean success = task.call();
		if (success) {
			JavaClassObject jco = fileManager.getMainJavaClassObject();
			DynamicClassLoader dynamicClassLoader = new DynamicClassLoader(this.parentClassLoader);
			try {
				List<JavaClassObject> innerClassJcos = fileManager.getInnerClassJavaClassObject();
				if (innerClassJcos != null && innerClassJcos.size() > 0) {
					for (JavaClassObject inner : innerClassJcos) {
						String name = inner.getName();
						name = name.substring(1, name.length() - 6).replace("/", ".");
						dynamicClassLoader.loadClass(name, inner);
					}
				}
				return jco.getBytes() ;
			} catch (Exception e) {
				e.printStackTrace();
				LOG.error(e);
			} catch (Error e) {
				LOG.error(e);
				throw new CodeException(e.toString());
			} finally {
				if (dynamicClassLoader != null) {
					dynamicClassLoader.close();
				}
			}
		} else {
			StringBuilder error = new StringBuilder();
			for (Diagnostic<?> diagnostic : diagnostics.getDiagnostics()) {
				error.append(compilePrint(diagnostic));
			}
			throw new CodeException(error.toString());
		}

		return null ;
	}

	public <T> T javaCodeToObject(String fullClassName, String javaCode) throws IllegalAccessException, InstantiationException, IOException, CodeException {
		return (T) javaCodeToClass(fullClassName, javaCode).newInstance();
	}

	private StringBuilder compilePrint(Diagnostic<?> diagnostic) {
		StringBuilder res = new StringBuilder();
		res.append("Code:[" + diagnostic.getCode() + "]\n");
		res.append("Kind:[" + diagnostic.getKind() + "]\n");
		res.append("Position:[" + diagnostic.getPosition() + "]\n");
		res.append("Start Position:[" + diagnostic.getStartPosition() + "]\n");
		res.append("End Position:[" + diagnostic.getEndPosition() + "]\n");
		res.append("Source:[" + diagnostic.getSource() + "]\n");
		res.append("Message:[" + diagnostic.getMessage(null) + "]\n");
		res.append("LineNumber:[" + diagnostic.getLineNumber() + "]\n");
		res.append("ColumnNumber:[" + diagnostic.getColumnNumber() + "]\n");
		return res;
	}

	public <T> T javaCodeToObject(String content) throws IllegalAccessException, InstantiationException, IOException, CodeException {
		String className = JavaSourceUtil.findFullName(content);
		if (StringUtil.isBlank(className)) {
			throw new ClassFormatError("can find class name ,please define it ! use javaCodeToObject(String fullClassName, String javaCode)");
		}
		return (T) this.javaCodeToObject(className, content);
	}

	public Class<?> javaCodeToClass(String content) throws IOException, CodeException {
		String className = JavaSourceUtil.findFullName(content);
		if (StringUtil.isBlank(className)) {
			throw new ClassFormatError("can find class name ,please define it ! use javaCodeToObject(String fullClassName, String javaCode)");
		}
		return this.javaCodeToClass(className, content);
	}

	public URLClassLoader getParentClassLoader() {
		return parentClassLoader;
	}

	public Class<?> javaCodeToClassFile(String string, String sourcePath) {
		// TODO Auto-generated method stub
		return null;
	}

}