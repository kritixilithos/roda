package org.kaivos.röda;

import static java.util.Collections.emptyList;

import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static org.kaivos.röda.Parser.parse;
import static org.kaivos.röda.Parser.parseStatement;
import static org.kaivos.röda.Parser.t;
import static org.kaivos.röda.RödaValue.BOOLEAN;
import static org.kaivos.röda.RödaValue.FUNCTION;
import static org.kaivos.röda.RödaValue.INTEGER;
import static org.kaivos.röda.RödaValue.LIST;
import static org.kaivos.röda.RödaValue.MAP;
import static org.kaivos.röda.RödaValue.NAMESPACE;
import static org.kaivos.röda.RödaValue.NFUNCTION;
import static org.kaivos.röda.RödaValue.REFERENCE;
import static org.kaivos.röda.RödaValue.STRING;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.kaivos.nept.parser.ParsingException;
import org.kaivos.nept.parser.TokenList;
import org.kaivos.röda.Parser.Annotation;
import org.kaivos.röda.Parser.Argument;
import org.kaivos.röda.Parser.Command;
import org.kaivos.röda.Parser.Expression;
import org.kaivos.röda.Parser.Function;
import org.kaivos.röda.Parser.KwArgument;
import org.kaivos.röda.Parser.Parameter;
import org.kaivos.röda.Parser.Program;
import org.kaivos.röda.Parser.Record;
import org.kaivos.röda.Parser.Statement;
import org.kaivos.röda.RödaStream.ISLineStream;
import org.kaivos.röda.RödaStream.OSStream;
import org.kaivos.röda.type.RödaBoolean;
import org.kaivos.röda.type.RödaFloating;
import org.kaivos.röda.type.RödaFunction;
import org.kaivos.röda.type.RödaInteger;
import org.kaivos.röda.type.RödaList;
import org.kaivos.röda.type.RödaMap;
import org.kaivos.röda.type.RödaNamespace;
import org.kaivos.röda.type.RödaNativeFunction;
import org.kaivos.röda.type.RödaRecordInstance;
import org.kaivos.röda.type.RödaReference;
import org.kaivos.röda.type.RödaString;

public class Interpreter {

	/*** INTERPRETER ***/

	public static class RödaScope {
		Interpreter context;
		Optional<RödaScope> parent;
		Map<String, RödaValue> map;
		Map<String, Datatype> typeargs;
		Map<String, RecordDeclaration> records = new HashMap<>();
		RödaScope(Interpreter context, Optional<RödaScope> parent) {
			this.context = context;
			this.parent = parent;
			this.map = new HashMap<>();
			this.typeargs = new HashMap<>();
		}
		public RödaScope(Interpreter context, RödaScope parent) {
			this(context, Optional.of(parent));
		}

		public synchronized RödaValue resolve(String name) {
			if (map.containsKey(name)) return map.get(name);
			if (parent.isPresent()) return parent.get().resolve(name);
			return null;
		}

		public synchronized void set(String name, RödaValue value) {
			if (map.containsKey(name))
				map.put(name, value);
			else if (parent.isPresent() && parent.get().resolve(name) != null)
				parent.get().set(name, value);
			else {
				map.put(name, value);
			}
		}

		public synchronized void setLocal(String name, RödaValue value) {
			map.put(name, value);
		}

		public void addTypearg(String name, Datatype value) {
			if (getTypearg(name) != null) {
				error("can't override typeargument '" + name + "'");
			}
			typeargs.put(name, value);
		}

		public Datatype getTypearg(String name) {
			if (typeargs.containsKey(name)) {
				return typeargs.get(name);
			}
			if (parent.isPresent()) {
				return parent.get().getTypearg(name);
			}
			return null;
		}

		public Datatype substitute(Datatype type) {
			Datatype typearg = getTypearg(type.name);
			if (typearg != null) {
				if (!type.subtypes.isEmpty())
					error("a typeparameter can't have subtypes");
				return typearg;
			}
			List<Datatype> subtypes = new ArrayList<>();
			for (Datatype t : type.subtypes) {
				subtypes.add(substitute(t));
			}
			return new Datatype(type.name, subtypes);
		}
		
		public Map<String, Record> getRecords() {
			Map<String, Record> records = new HashMap<>();
			if (parent.isPresent()) {
				records.putAll(parent.get().getRecords());
			}
			this.records.values().forEach(r -> records.put(r.tree.name, r.tree));
			return Collections.unmodifiableMap(records);
		}
		
		public Map<String, RecordDeclaration> getRecordDeclarations() {
			Map<String, RecordDeclaration> records = new HashMap<>();
			if (parent.isPresent()) {
				records.putAll(parent.get().getRecordDeclarations());
			}
			records.putAll(this.records);
			return Collections.unmodifiableMap(records);
		}

		public void preRegisterRecord(Record record) {
			records.put(record.name, new RecordDeclaration(record, this, context.createRecordClassReflection(record, this)));
		}

		public void postRegisterRecord(Record record) {
			context.createFieldReflections(record, records.get(record.name).reflection, this);
		}
		
		public void registerRecord(RecordDeclaration record) {
			records.put(record.tree.name, record);
		}
	}
	
	public static class RecordDeclaration {
		Record tree;
		RödaScope declarationScope;
		RödaValue reflection;
		
		public RecordDeclaration(Record record, RödaScope declarationScope, RödaValue reflection) {
			this.tree = record;
			this.declarationScope = declarationScope;
			this.reflection = reflection;
		}
	}

	public RödaScope G = new RödaScope(this, Optional.empty());

	RödaStream STDIN, STDOUT;

	public File currentDir = new File(System.getProperty("user.dir"));

	private void initializeIO() {
		InputStreamReader ir = new InputStreamReader(System.in);
		BufferedReader in = new BufferedReader(ir);
		PrintWriter out = new PrintWriter(System.out);
		STDIN = new ISLineStream(in);
		STDOUT = new OSStream(out);
	}
	
	private static Record errorSubtype(String name) {
		return new Record(name,
				emptyList(),
				Arrays.asList(new Record.SuperExpression(new Datatype("Error"), emptyList())),
				emptyList(),
				false);
	}
	
	private static Record errorSubtype(String name, String... superTypes) {
		return new Record(name,
				emptyList(),
				Arrays.asList(superTypes).stream()
					.map(t -> new Record.SuperExpression(new Datatype(t), emptyList()))
					.collect(toList()),
				emptyList(),
				false);
	}

	private static final Record errorRecord, typeRecord, fieldRecord;
	private static final Record
		javaErrorRecord,
		leakyPipeErrorRecord,
		emptyStreamErrorRecord,
		fullStreamErrorRecord,
		illegalArgumentsErrorRecord,
		unknownNameErrorRecord,
		typeMismatchErrorRecord,
		outOfBoundsErrorRecord;
	static {
		errorRecord = new Record("Error",
				emptyList(),
				emptyList(),
				Arrays.asList(new Record.Field("message", new Datatype("string")),
						new Record.Field("stack",
								new Datatype("list", Arrays.asList(new Datatype("string")))),
						new Record.Field("javastack",
								new Datatype("list", Arrays.asList(new Datatype("string")))),
						new Record.Field("causes",
								new Datatype("list", Arrays.asList(new Datatype("Error"))))
						),
				false);
		typeRecord = new Record("Type",
				emptyList(),
				emptyList(),
				Arrays.asList(new Record.Field("name", new Datatype("string")),
						new Record.Field("annotations", new Datatype("list")),
						new Record.Field("fields",
								new Datatype("list", Arrays.asList(new Datatype("Field")))),
						new Record.Field("newInstance", new Datatype("function"))
						),
				false);
		fieldRecord = new Record("Field",
				emptyList(),
				emptyList(),
				Arrays.asList(new Record.Field("name", new Datatype("string")),
						new Record.Field("annotations", new Datatype("list")),
						new Record.Field("type", new Datatype("Type")),
						new Record.Field("get", new Datatype("function")),
						new Record.Field("set", new Datatype("function"))
						),
				false);
		
		javaErrorRecord = errorSubtype("JavaError");
		leakyPipeErrorRecord = errorSubtype("LeakyPipeError");
		emptyStreamErrorRecord = errorSubtype("EmptyStreamError");
		fullStreamErrorRecord = errorSubtype("FullStreamError");
		illegalArgumentsErrorRecord = errorSubtype("IllegalArgumentsError");
		unknownNameErrorRecord = errorSubtype("UnknownNameError");
		typeMismatchErrorRecord = errorSubtype("TypeMismatchError", "IllegalArgumentsError");
		outOfBoundsErrorRecord = errorSubtype("OutOfBoundsError", "IllegalArgumentsError");
	}
	private static final Record[] builtinRecords = {
			errorRecord, typeRecord, fieldRecord,
			leakyPipeErrorRecord,
			javaErrorRecord,
			emptyStreamErrorRecord,
			fullStreamErrorRecord,
			illegalArgumentsErrorRecord,
			unknownNameErrorRecord,
			typeMismatchErrorRecord,
			outOfBoundsErrorRecord
			};
	private static final Map<String, Record> builtinRecordMap = new HashMap<>();
	static {
		for (Record r : builtinRecords) {
			builtinRecordMap.put(r.name, r);
		}
	}

	{
		Builtins.populate(this);
		for (Record r : builtinRecords) {
			G.preRegisterRecord(r);
		}
		for (Record r : builtinRecords) {
			G.postRegisterRecord(r);
		}

		G.setLocal("ENV", RödaMap.of(System.getenv().entrySet().stream()
				.collect(toMap(e -> e.getKey(),
						e -> RödaString.of(e.getValue())))));
	}

	private RödaValue createRecordClassReflection(Record record, RödaScope scope) {
		if (enableDebug) callStack.get().push("creating reflection object of record "
				+ record.name + "\n\tat <runtime>");
		RödaValue typeObj = RödaRecordInstance.of(typeRecord, emptyList(), G.getRecords());
		typeObj.setField("name", RödaString.of(record.name));
		typeObj.setField("annotations", evalAnnotations(record.annotations, scope));
		typeObj.setField("newInstance", RödaNativeFunction
				.of("Type.newInstance",
						(ta, a, k, s, i, o) -> {
							o.push(newRecord(new Datatype(record.name), ta, a, scope));
						}, emptyList(), false));
		if (enableDebug) callStack.get().pop();
		return typeObj;
	}

	private void createFieldReflections(Record record, RödaValue typeObj, RödaScope scope) {
		if (enableDebug) callStack.get().push("creating field reflection objects of record "
				+ record.name + "\n\tat <runtime>");
		typeObj.setField("fields", RödaList.of("Field", record.fields.stream()
				.map(f -> createFieldReflection(record, f, scope))
				.collect(toList())));
		if (enableDebug) callStack.get().pop();
	}

	private RödaValue createFieldReflection(Record record, Record.Field field, RödaScope scope) {
		if (enableDebug) callStack.get().push("creating reflection object of field "
				+ record.name + "." + field.name + "\n\tat <runtime>");
		RödaValue fieldObj = RödaRecordInstance.of(fieldRecord, emptyList(), G.getRecords());
		fieldObj.setField("name", RödaString.of(field.name));
		fieldObj.setField("annotations", evalAnnotations(field.annotations, scope));
		fieldObj.setField("get", RödaNativeFunction
				.of("Field.get",
						(ta, a, k, s, i, o) -> {
							RödaValue obj = a.get(0);
							if (!obj.is(new Datatype(record.name))) {
								illegalArguments("illegal argument for Field.get: "
										+ record.name + " required, got " + obj.typeString());
							}
							o.push(obj.getField(field.name));
						}, Arrays.asList(new Parameter("object", false)), false));
		fieldObj.setField("set", RödaNativeFunction
				.of("Field.set",
						(ta, a, k, s, i, o) -> {
							RödaValue obj = a.get(0);
							if (!obj.is(new Datatype(record.name))) {
								illegalArguments("illegal argument for Field.get: "
										+ record.name + " required, got " + obj.typeString());
							}
							RödaValue val = a.get(1);
							obj.setField(field.name, val);
						}, Arrays.asList(new Parameter("object", false),
								new Parameter("value", false)), false));
		if (scope.getRecordDeclarations().containsKey(field.type.name))
			fieldObj.setField("type", scope.getRecordDeclarations().get(field.type.name).reflection);
		if (enableDebug) callStack.get().pop();
		return fieldObj;
	}

	private RödaValue evalAnnotations(List<Annotation> annotations, RödaScope scope) {
		return annotations.stream()
				.map(a -> {
					RödaScope annotationScope = scope;
					for (String var : a.namespace) {
						RödaValue val = annotationScope.resolve(var);
						if (val == null)
							unknownName("namespace '" + var + "' not found");
						if (!val.is(NAMESPACE)) {
							typeMismatch("type mismatch: expected namespace, got " + val.typeString());
						}
						annotationScope = val.scope();
					}
					RödaValue function = annotationScope.resolve(a.name);
					if (function == null) unknownName("annotation function '" + a.name + "' not found");
					List<RödaValue> args = flattenArguments(a.args.arguments, scope,
							RödaStream.makeEmptyStream(),
							RödaStream.makeStream(),
							false);
					Map<String, RödaValue> kwargs = kwargsToMap(a.args.kwarguments, scope,
							RödaStream.makeEmptyStream(),
							RödaStream.makeStream(), false);
					RödaStream _out = RödaStream.makeStream();
					exec(a.file, a.line, function, emptyList(), args, kwargs,
							G, RödaStream.makeEmptyStream(), _out);
					_out.finish();
					RödaValue list = _out.readAll();
					return list.list();
				})
				.collect(() -> RödaList.empty(),
						(list, values) -> { list.addAll(values); },
						(list, list2) -> { list.addAll(list2.list()); });
	}

	public static ExecutorService executor = Executors.newCachedThreadPool();

	public static void shutdown() {
		executor.shutdown();
	}

	public Interpreter() {
		initializeIO();
	}

	public Interpreter(RödaStream in, RödaStream out) {
		STDIN = in;
		STDOUT = out;
	}

	/* kutsupino */
	
	public static ThreadLocal<ArrayDeque<String>> callStack = new InheritableThreadLocal<ArrayDeque<String>>() {
		@Override protected ArrayDeque<String> childValue(ArrayDeque<String> parentValue) {
			return new ArrayDeque<>(parentValue);
		}
	};

	static { callStack.set(new ArrayDeque<>()); }
	
	/* profiloija */
	
	public static class ProfilerData {
		public final String function;
		public int invocations = 0;
		public long time = 0;
		
		public ProfilerData(String function) {
			this.function = function;
		}
	}
	
	public static Map<String, ProfilerData> profilerData = new HashMap<>();
	
	private synchronized void updateProfilerData(String function, long value) {
		ProfilerData data;
		if (!profilerData.containsKey(function)) profilerData.put(function, data = new ProfilerData(function));
		else data = profilerData.get(function);
		data.time += value;
		data.invocations++;
	}
	
	private static ThreadLocal<ArrayDeque<Timer>> timerStack = ThreadLocal.withInitial(ArrayDeque::new);
	
	public boolean enableDebug = true, enableProfiling = false;

	@SuppressWarnings("serial")
	public static class RödaException extends RuntimeException {
		private Throwable[] causes;
		private Deque<String> stack;
		private RödaValue errorObject;
		private RödaException(String message, Deque<String> stack, RödaValue errorObject) {
			super(message);
			this.causes = new Throwable[0];
			this.stack = stack;
			this.errorObject = errorObject;
		}

		private RödaException(Throwable cause, Deque<String> stack, RödaValue errorObject) {
			super(cause);
			this.stack = stack;
			this.errorObject = errorObject;
		}

		private RödaException(Throwable[] causes, Deque<String> stack, RödaValue errorObject) {
			super(causes.length == 1 ? causes[0].getClass().getName() + ": " + causes[0].getMessage()
					: "multiple threads crashed", causes[0]);
			this.causes = causes;
			this.stack = stack;
			this.errorObject = errorObject;
		}
		
		public Throwable[] getCauses() {
			return causes;
		}

		public Deque<String> getStack() {
			return stack;
		}

		public RödaValue getErrorObject() {
			return errorObject;
		}
	}

	private static RödaValue makeErrorObject(Record record, String message,
			StackTraceElement[] javaStackTrace, Throwable... causes) {
		RödaValue errorObject = RödaRecordInstance
				.of(record,
						emptyList(),
						builtinRecordMap); // Purkkaa, mutta toimii: errorilla ei ole ulkoisia riippuvuuksia
		errorObject.setField("message", RödaString.of(message));
		errorObject.setField("stack", RödaList.of("string", callStack.get().stream()
				.map(RödaString::of).collect(toList())));
		errorObject.setField("javastack", RödaList.of("string", Arrays.stream(javaStackTrace)
				.map(StackTraceElement::toString).map(RödaString::of)
				.collect(toList())));
		errorObject.setField("causes", RödaList.of("Error", Arrays.stream(causes)
				.map(cause -> cause instanceof RödaException ? ((RödaException) cause).getErrorObject()
						: makeErrorObject(javaErrorRecord,
								cause.getClass().getName() + ": " + cause.getMessage(), cause.getStackTrace()))
				.collect(toList())));
		return errorObject;
	}

	private static RödaException createRödaException(Record record, String message) {
		RödaValue errorObject = makeErrorObject(record, message, Thread.currentThread().getStackTrace());
		return new RödaException(message, new ArrayDeque<>(callStack.get()), errorObject);
	}
	
	public static void error(String message) {
		throw createRödaException(errorRecord, message);
	}
	
	public static void emptyStream(String message) {
		throw createRödaException(emptyStreamErrorRecord, message);
	}
	
	public static void fullStream(String message) {
		throw createRödaException(fullStreamErrorRecord, message);
	}
	
	public static void illegalArguments(String message) {
		throw createRödaException(illegalArgumentsErrorRecord, message);
	}
	
	public static void unknownName(String message) {
		throw createRödaException(unknownNameErrorRecord, message);
	}
	
	public static void typeMismatch(String message) {
		throw createRödaException(typeMismatchErrorRecord, message);
	}
	
	public static void outOfBounds(String message) {
		throw createRödaException(outOfBoundsErrorRecord, message);
	}
	
	private static RödaException createRödaException(Throwable...causes) {
		if (causes.length == 1 && causes[0] instanceof RödaException) return (RödaException) causes[0];
		
		String message;
		if (causes.length == 1) message = causes[0].getClass().getName() + ": " + causes[0].getMessage();
		else message = "multiple threads crashed";
		
		StackTraceElement[] javaStackTrace;
		if (causes.length == 1) javaStackTrace = causes[0].getStackTrace();
		else javaStackTrace = Thread.currentThread().getStackTrace();
		
		RödaValue errorObject = makeErrorObject(causes.length == 1 ? javaErrorRecord : errorRecord,
				message, javaStackTrace, causes);
		return new RödaException(causes, new ArrayDeque<>(callStack.get()), errorObject);
	}

	public static void error(Throwable... causes) {
		throw createRödaException(causes);
	}

	public static void error(RödaValue errorObject) {
		RödaException e = new RödaException(errorObject.getField("message").str(), new ArrayDeque<>(callStack.get()), errorObject);
		throw e;
	}

	@SuppressWarnings("unused")
	private static void printStackTrace() {
		for (String step : callStack.get()) {
			System.err.println(step);
		}
	}

	public void interpret(String code) {
		interpret(code, "<input>");
	}

	public void interpret(String code, String filename) {
		interpret(code, new ArrayList<>(), "<input>");
	}

	public void interpret(String code, List<RödaValue> args, String filename) {
		try {
			load(code, filename, G);

			RödaValue main = G.resolve("main");
			if (main == null) return;
			if (!main.is(FUNCTION) || main.is(NFUNCTION))
				typeMismatch("The variable 'main' must be a function");

			exec("<runtime>", 0, main, emptyList(), args, Collections.emptyMap(), G, STDIN, STDOUT);
		} catch (ParsingException|RödaException e) {
			throw e;
		} catch (Exception e) {
			error(e);
		}
	}

	public void load(String code, String filename, RödaScope scope, boolean overwrite) {
		try {
			Program program = parse(t.tokenize(code, filename));
			for (List<Statement> f : program.preBlocks) {
				execBlock(f, scope);
			}
			for (Function f : program.functions) {
				scope.setLocal(f.name, RödaFunction.of(f, scope));
			}
			for (Record r : program.records) {
				scope.preRegisterRecord(r);
			}
			for (Record r : program.records) {
				scope.postRegisterRecord(r);
			}
			for (List<Statement> f : program.postBlocks) {
				execBlock(f, scope);
			}
		} catch (ParsingException|RödaException e) {
			throw e;
		} catch (Exception e) {
			error(e);
		}
	}
	
	private void execBlock(List<Statement> block, RödaScope scope) {
		RödaStream in = RödaStream.makeEmptyStream();
		RödaStream out = RödaStream.makeEmptyStream();
		for (Statement s : block) {
			evalStatement(s, scope, in, out, false);
		}
	}
	
	public void load(String code, String filename, RödaScope scope) {
		load(code, filename, scope, true);
	}

	public void loadFile(File file, RödaScope scope, boolean overwrite) {
		try {
			BufferedReader in = new BufferedReader(
					new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
			String code = "";
			String line = "";
			while ((line = in.readLine()) != null) {
				code += line + "\n";
			}
			in.close();
			load(code, file.getName(), scope, overwrite);
		} catch (IOException e) {
			error(e);
		}
	}
	
	public void loadFile(File file, RödaScope scope) {
		loadFile(file, scope, true);
	}

	public void interpretStatement(String code, String filename) {
		try {
			TokenList tl = t.tokenize(code, filename);
			Statement statement = parseStatement(tl);
			tl.accept("<EOF>");
			evalStatement(statement, G, STDIN, STDOUT, false);
		} catch (RödaException e) {
			throw e;
		} catch (ParsingException e) {
			throw e;
		} catch (Exception e) {
			error(e);
		} 
	}

	private Parameter getParameter(RödaValue function, int i) {
		assert function.is(FUNCTION);
		boolean isNative = function.is(NFUNCTION);
		List<Parameter> parameters = isNative ? function.nfunction().parameters
				: function.function().parameters;
		boolean isVarargs = isNative ? function.nfunction().isVarargs : function.function().isVarargs;
		if (isVarargs && i >= parameters.size()-1) return parameters.get(parameters.size()-1);
		else if (i >= parameters.size()) return null;
		else return parameters.get(i);
	}

	private boolean isReferenceParameter(RödaValue function, int i) {
		Parameter p = getParameter(function, i);
		if (p == null) return false; // tästä tulee virhe myöhemmin
		return p.reference;
	}

	private List<Parameter> getKwParameters(RödaValue function) {
		if (function.is(NFUNCTION)) {
			return function.nfunction().kwparameters;
		}
		if (function.is(FUNCTION)) {
			return function.function().kwparameters;
		}
		return emptyList();
	}

	public static void checkReference(String function, RödaValue arg) {
		if (!arg.is(REFERENCE)) {
			typeMismatch("illegal argument for '" + function
					+ "': reference expected (got " + arg.typeString() + ")");
		}
	}

	public static void checkList(String function, RödaValue arg) {
		if (!arg.is(LIST)) {
			typeMismatch("illegal argument for '" + function
					+ "': list expected (got " + arg.typeString() + ")");
		}
	}

	public static void checkMap(String function, RödaValue arg) {
		if (!arg.is(MAP)) {
			typeMismatch("illegal argument for '" + function
					+ "': list expected (got " + arg.typeString() + ")");
		}
	}

	public static void checkListOrString(String function, RödaValue arg) {
		if (!arg.is(LIST) && !arg.is(STRING)) {
			typeMismatch("illegal argument for '" + function
					+ "': list or string expected (got " + arg.typeString() + ")");
		}
	}

	public static void checkListOrNumber(String function, RödaValue arg) {
		if (!arg.is(LIST) && !arg.is(INTEGER)) {
			typeMismatch("illegal argument for '" + function
					+ "': list or integer expected (got " + arg.typeString() + ")");
		}
	}

	public static void checkString(String function, RödaValue arg) {
		if (!arg.is(STRING)) {
			typeMismatch("illegal argument for '" + function
					+ "': string expected (got " + arg.typeString() + ")");
		}
	}

	public static void checkNumber(String function, RödaValue arg) {
		if (!arg.is(INTEGER)) {
			typeMismatch("illegal argument for '" + function
					+ "': integer expected (got " + arg.typeString() + ")");
		}
	}

	public static void checkBoolean(String function, RödaValue arg) {
		if (!arg.is(BOOLEAN)) {
			typeMismatch("illegal argument for '" + function
					+ "': boolean expected (got " + arg.typeString() + ")");
		}
	}

	public static void checkFunction(String function, RödaValue arg) {
		if (!arg.is(FUNCTION)) {
			typeMismatch("illegal argument for '" + function
					+ "': function expected (got " + arg.typeString() + ")");
		}
	}

	public static void checkArgs(String function, int required, int got) {
		if (got > required) argumentOverflow(function, required, got);
		if (got < required) argumentUnderflow(function, required, got);
	}

	private static void checkArgs(String name, boolean isVarargs,
			List<Parameter> parameters,
			List<Parameter> kwparameters,
			List<RödaValue> args, Map<String, RödaValue> kwargs,
			RödaScope scope) {
		if (!isVarargs) {
			checkArgs(name, parameters.size(), args.size());
		} else {
			if (args.size() < parameters.size()-1)
				argumentUnderflow(name, parameters.size()-1, args.size());
		}

		for (int i = 0; i < Math.min(args.size(), parameters.size()); i++) {
			if (parameters.get(i).type == null) continue;
			Datatype t = scope.substitute(parameters.get(i).type);
			if (!args.get(i).is(t))
				typeMismatch("illegal argument '" + parameters.get(i).name + "' for '"+name+"': "
						+ t + " expected (got "
						+ args.get(i).typeString() + ")");
		}

		for (Parameter par : kwparameters) {
			if (!kwargs.containsKey(par.name))
				illegalArguments("illegal arguments for '" + name + "': kw argument " + par.name + " not found");
			if (par.type == null) continue;
			Datatype t = scope.substitute(par.type);
			if (!kwargs.get(par.name).is(t))
				typeMismatch("illegal argument '" + par.name + "' for '"+name+"': "
						+ t + " expected (got "
						+ kwargs.get(par.name).typeString() + ")");
		}
	}

	public static void argumentOverflow(String function, int required, int got) {
		illegalArguments("illegal number of arguments for '" + function
				+ "': at most " + required + " required (got " + got + ")");
	}

	public static void argumentUnderflow(String function, int required, int got) {
		illegalArguments("illegal number of arguments for '" + function
				+ "': at least " + required + " required (got " + got + ")");
	}
	
	private RödaValue resolveArgument(RödaValue val, boolean isReferenceParameter) {
		// jos kyseessä ei ole viittausparametri, resolvoidaan viittaus
		if (val.is(REFERENCE) && !isReferenceParameter) {
			RödaValue rval = val.resolve(true);
			if (rval.is(REFERENCE)) rval = rval.resolve(true); // tuplaviittaus
			return rval;
		}
		// jos kyseessä on viittausparametri, resolvoidaan viittaus vain, jos kyseessä on tuplaviittaus
		if (val.is(REFERENCE) && isReferenceParameter) {
			RödaValue rval = val.unsafeResolve();
			if (rval != null && rval.is(REFERENCE)) return rval; // tuplaviittaus
			else return val;
		}
		return val;
	}

	public void exec(String file, int line,
			RödaValue value, List<Datatype> typeargs,
			List<RödaValue> rawArgs, Map<String, RödaValue> rawKwArgs,
			RödaScope scope, RödaStream in, RödaStream out) {
		List<RödaValue> args = new ArrayList<>();
		Map<String, RödaValue> kwargs = new HashMap<>();
		int i = 0;
		for (RödaValue val : rawArgs) {
			val = resolveArgument(val, value.is(FUNCTION) && isReferenceParameter(value, i));
			args.add(val);
			i++;
		}

		for (Parameter kwpar : getKwParameters(value)) {
			if (!rawKwArgs.containsKey(kwpar.name)) {
				RödaValue defaultVal = evalExpression(kwpar.defaultValue, G,
						RödaStream.makeEmptyStream(),
						RödaStream.makeStream()).impliciteResolve();
				kwargs.put(kwpar.name, defaultVal);
				continue;
			}
			RödaValue val = rawKwArgs.get(kwpar.name);
			val = resolveArgument(val, false);
			kwargs.put(kwpar.name, val);
		}
		
		if (value.is(NFUNCTION) && value.nfunction().isKwVarargs) {
			for (Entry<String, RödaValue> arg : rawKwArgs.entrySet()) {
				if (!kwargs.containsKey(arg.getKey())) {
					kwargs.put(arg.getKey(), resolveArgument(arg.getValue(), false));
				}
			}
		}

		if (enableProfiling) {
			ArrayDeque<Timer> ts = timerStack.get();
			if (!ts.isEmpty()) {
				ts.peek().stop();
			}
			Timer t = new Timer();
			ts.push(t);
			t.start();
		}
		if (enableDebug) {
			if (args.size() > 0) {
				callStack.get().push("calling " + value.str()
					+ " with argument" + (args.size() == 1 ? " " : "s ")
					+ args.stream()
						.map(RödaValue::str)
						.collect(joining(", "))
					+ "\n\tat " + file + ":" + line);
			}
			else {
				callStack.get().push("calling " + value.str()
					+ " with no arguments\n"
					+ "\tat " + file + ":" + line);
			}
		}
		try {
			execWithoutErrorHandling(value, typeargs, args, kwargs, scope, in, out);
		}
		catch (RödaException e) { throw e; }
		catch (Throwable e) { error(e); }
		finally {
			if (enableDebug) callStack.get().pop();
			if (enableProfiling) {
				ArrayDeque<Timer> ts = timerStack.get();
				Timer t = ts.pop();
				t.stop();
				updateProfilerData(value.str(), t.timeNanos());
				if (!ts.isEmpty()) {
					ts.peek().start();
				}
			}
		}
	}

	@SuppressWarnings("serial")
	private static class ReturnException extends RuntimeException { }
	private static final ReturnException RETURN_EXCEPTION = new ReturnException();

	public void execWithoutErrorHandling(
			RödaValue value,
			List<Datatype> typeargs,
			List<RödaValue> args, Map<String, RödaValue> kwargs,
			RödaScope scope, RödaStream in, RödaStream out) {

		//System.err.println("exec " + value + "("+args+") " + in + " -> " + out);
		if (value.is(LIST)) {
			for (RödaValue item : value.list())
				out.push(item);
			return;
		}
		if (value.is(FUNCTION) && !value.is(NFUNCTION)) {
			boolean isVarargs = value.function().isVarargs;
			List<String> typeparams = value.function().typeparams;
			List<Parameter> parameters = value.function().parameters;
			List<Parameter> kwparameters = value.function().kwparameters;
			String name = value.function().name;

			// joko nimettömän funktion paikallinen scope tai ylätason scope
			RödaScope newScope = value.localScope() == null
					? new RödaScope(this, G) : new RödaScope(this, value.localScope());
			
			newScope.setLocal("caller_namespace", RödaNamespace.of(scope));
			
			if (typeparams.size() != typeargs.size())
				illegalArguments("illegal number of typearguments for '" + name + "': "
						+ typeparams.size() + " required, got " + typeargs.size());
			for (int i = 0; i < typeparams.size(); i++) {
				newScope.addTypearg(typeparams.get(i), typeargs.get(i));
			}

			checkArgs(name, isVarargs, parameters, kwparameters, args, kwargs, newScope);

			int j = 0;
			for (Parameter p : parameters) {
				if (isVarargs && j == parameters.size()-1) break;
				newScope.setLocal(p.name, args.get(j++));
			}
			if (isVarargs) {
				RödaValue argslist = RödaList.of(new ArrayList<>());
				if (args.size() >= parameters.size()) {
					for (int k = parameters.size()-1; k < args.size(); k++) {
						argslist.add(args.get(k));
					}
				}
				newScope.setLocal(parameters.get(parameters.size()-1).name, argslist);
			}
			for (Parameter p : kwparameters) {
				newScope.setLocal(p.name, kwargs.get(p.name));
			}
			for (Statement s : value.function().body) {
				try {
					evalStatement(s, newScope, in, out, false);
				} catch (ReturnException e) {
					break;
				}
			}
			return;
		}
		if (value.is(NFUNCTION)) {
			checkArgs(value.nfunction().name, value.nfunction().isVarargs,
					value.nfunction().parameters, value.nfunction().kwparameters,
					args, kwargs, scope);
			value.nfunction().body.exec(typeargs, args, kwargs, scope, in, out);
			return;
		}
		typeMismatch("can't execute a value of type " + value.typeString());
	}

	private void evalStatement(Statement statement, RödaScope scope,
			RödaStream in, RödaStream out, boolean redirected) {
		RödaStream _in = in;
		int i = 0;
		Runnable[] runnables = new Runnable[statement.commands.size()];
		for (Command command : statement.commands) {
			boolean last = i == statement.commands.size()-1;
			RödaStream _out = last ? out : RödaStream.makeStream();
			Runnable tr = evalCommand(command, scope,
					in, out,
					_in, _out);
			runnables[i] = () -> {
				try {
					tr.run();
				} finally {
					// sulje virta jos se on putki tai muulla tavalla uudelleenohjaus
					if (!last || redirected)
						_out.finish();
				}
			};
			_in = _out;
			i++;
		}
		if (runnables.length == 1) runnables[0].run();
		else {
			Future<?>[] futures = new Future<?>[runnables.length];
			i = 0;
			for (Runnable r : runnables) {
				futures[i++] = executor.submit(r);
			}
			List<ExecutionException> exceptions = new ArrayList<>();
			try {
				i = futures.length;
				while (i --> 0) {
					try {
						futures[i].get();
					} catch (ExecutionException e) {
						exceptions.add(e);
					}
				}
			} catch (InterruptedException e) {
				error(e);
			} 
			
			if (!exceptions.isEmpty()) {
				error(exceptions.stream().map(e -> {
					if (e.getCause() instanceof RödaException) {
						return (RödaException) e.getCause();
					}
					if (e.getCause() instanceof ReturnException) {
						return createRödaException(leakyPipeErrorRecord, "cannot pipe a return command");
					}
					if (e.getCause() instanceof BreakOrContinueException) {
						return createRödaException(leakyPipeErrorRecord, "cannot pipe a break or continue command");
					}
					return createRödaException(e.getCause());
				}).toArray(n -> new RödaException[n]));
			}
		}
	}

	@SuppressWarnings("serial")
	private static class BreakOrContinueException extends RuntimeException {
		private boolean isBreak;
		private BreakOrContinueException(boolean isBreak) { this.isBreak = isBreak; }
	}
	private static final BreakOrContinueException BREAK_EXCEPTION = new BreakOrContinueException(true);
	private static final BreakOrContinueException CONTINUE_EXCEPTION = new BreakOrContinueException(false);

	private List<RödaValue> flattenArguments(List<Argument> arguments,
			RödaScope scope,
			RödaStream in, RödaStream out,
			boolean canResolve) {
		List<RödaValue> args = new ArrayList<>();
		for (Argument arg : arguments) {
			RödaValue value = evalExpression(arg.expr, scope, in, out, true);
			if (canResolve || arg.flattened) value = value.impliciteResolve();
			if (arg.flattened) {
				checkList("*", value);
				args.addAll(value.list());
			}
			else args.add(value);
		}
		return args;
	}

	private Map<String, RödaValue> kwargsToMap(List<KwArgument> arguments,
			RödaScope scope,
			RödaStream in, RödaStream out,
			boolean canResolve) {
		Map<String, RödaValue> map = new HashMap<>();
		for (KwArgument arg : arguments) {
			RödaValue value = evalExpression(arg.expr, scope, in, out, true);
			if (canResolve) value = value.impliciteResolve();
			map.put(arg.name, value);
		}
		return map;
	}

	public Runnable evalCommand(Command cmd,
			RödaScope scope,
			RödaStream in, RödaStream out,
			RödaStream _in, RödaStream _out) {
		if (cmd.type == Command.Type.NORMAL) {
			RödaValue function = evalExpression(cmd.name, scope, in, out);
			List<Datatype> typeargs = cmd.typearguments.stream()
					.map(scope::substitute).collect(toList());
			List<RödaValue> args = flattenArguments(cmd.arguments.arguments, scope, in, out, false);
			Map<String, RödaValue> kwargs = kwargsToMap(cmd.arguments.kwarguments, scope, in, out, false);
			Runnable r = () -> {
				exec(cmd.file, cmd.line, function, typeargs, args, kwargs, scope, _in, _out);
			};
			return r;
		}
		
		if (cmd.type == Command.Type.DEL) {
			Expression e = cmd.name;
			if (e.type != Expression.Type.ELEMENT
					&& e.type != Expression.Type.SLICE)
				error("bad lvalue for del: " + e.asString());
			if (e.type == Expression.Type.ELEMENT) {
				return () -> {
					RödaValue list = evalExpression(e.sub, scope, in, out).impliciteResolve();
					RödaValue index = evalExpression(e.index, scope, in, out)
							.impliciteResolve();
					list.del(index);
				};
			}
			else if (e.type == Expression.Type.SLICE) {
				return () -> {
					RödaValue list = evalExpression(e.sub, scope, in, out).impliciteResolve();
					RödaValue index1 = evalExpression(e.index1, scope, in, out)
							.impliciteResolve();
					RödaValue index2 = evalExpression(e.index2, scope, in, out)
							.impliciteResolve();
					list.delSlice(index1, index2);
				};
			}
		}

		if (cmd.type == Command.Type.VARIABLE) {
			List<RödaValue> args = flattenArguments(cmd.arguments.arguments, scope, in, out, true);
			Expression e = cmd.name;
			if (e.type != Expression.Type.VARIABLE
					&& e.type != Expression.Type.ELEMENT
					&& e.type != Expression.Type.SLICE
					&& e.type != Expression.Type.FIELD)
				error("bad lvalue for '" + cmd.operator + "': " + e.asString());
			Consumer<RödaValue> assign, assignLocal;
			if (e.type == Expression.Type.VARIABLE) {
				assign = v -> {
					RödaValue value = scope.resolve(e.variable);
					if (value == null || !value.is(REFERENCE))
						value = RödaReference.of(e.variable, scope);
					value.assign(v);
				};
				assignLocal = v -> {
					RödaValue value = scope.resolve(e.variable);
					if (value == null || !value.is(REFERENCE))
						value = RödaReference.of(e.variable, scope);
					value.assignLocal(v);
				};
			}
			else if (e.type == Expression.Type.ELEMENT) {
				assign = v -> {
					RödaValue list = evalExpression(e.sub, scope, in, out).impliciteResolve();
					RödaValue index = evalExpression(e.index, scope, in, out).impliciteResolve();
					list.set(index, v);
				};
				assignLocal = assign;
			}
			else if (e.type == Expression.Type.SLICE) {
				assign = v -> {
					RödaValue list = evalExpression(e.sub, scope, in, out).impliciteResolve();
					RödaValue index1 = evalExpression(e.index1, scope, in, out).impliciteResolve();
					RödaValue index2 = evalExpression(e.index2, scope, in, out).impliciteResolve();
					list.setSlice(index1, index2, v);
				};
				assignLocal = assign;
			}
			else {
				assign = v -> {
					RödaValue record = evalExpression(e.sub, scope, in, out).impliciteResolve();
					record.setField(e.field, v);
				};
				assignLocal = assign;
			}
			Supplier<RödaValue> resolve = () -> evalExpression(e, scope, in, out).impliciteResolve();
			Runnable r;
			switch (cmd.operator) {
			case ":=": {
				r = () -> {
					if (args.size() > 1) argumentOverflow(":=", 1, args.size());
					assignLocal.accept(args.get(0));
				};
			} break;
			case "=": {
				r = () -> {
					if (args.size() > 1) argumentOverflow("=", 1, args.size());
					assign.accept(args.get(0));
				};
			} break;
			case "++": {
				r = () -> {
					RödaValue v = resolve.get();
					checkNumber("++", v);
					assign.accept(RödaInteger.of(v.integer()+1));
				};
			} break;
			case "--": {
				r = () -> {
					RödaValue v = resolve.get();
					checkNumber("--", v);
					assign.accept(RödaInteger.of(v.integer()-1));
				};
			} break;
			case "+=": {
				r = () -> {
					RödaValue v = resolve.get();
					checkListOrNumber("+=", v);
					if (v.is(LIST)) {
						v.add(args.get(0));
					}
					else {
						checkNumber("+=", args.get(0));
						assign.accept(RödaInteger.of(v.integer()+args.get(0).integer()));
					}
				};
			} break;
			case "-=": {
				r = () -> {
					RödaValue v = resolve.get();
					checkNumber("-=", v);
					checkNumber("-=", args.get(0));
					assign.accept(RödaInteger.of(v.integer()-args.get(0).integer()));
				};
			} break;
			case "*=": {
				r = () -> {
					RödaValue v = resolve.get();
					checkNumber("*=", v);
					checkNumber("*=", args.get(0));
					assign.accept(RödaInteger.of(v.integer()*args.get(0).integer()));
				};
			} break;
			case "/=": {
				r = () -> {
					RödaValue v = resolve.get();
					checkNumber("/=", v);
					checkNumber("/=", args.get(0));
					assign.accept(RödaInteger.of(v.integer()/args.get(0).integer()));
				};
			} break;
			case ".=": {
				r = () -> {
					RödaValue v = resolve.get();
					checkListOrString(".=", v);
					if (v.is(LIST)) {
						checkList(".=", args.get(0));
						ArrayList<RödaValue> newList = new ArrayList<>();
						newList.addAll(v.list());
						newList.addAll(args.get(0).list());
						assign.accept(RödaList.of(newList));
					}
					else {
						checkString(".=", args.get(0));
						assign.accept(RödaString.of(v.str()+args.get(0).str()));
					}
				};
			} break;
			case "~=": {
				r = () -> {
					RödaValue rval = resolve.get();
					checkString(".=", rval);
					boolean quoteMode = false; // TODO: päätä, pitääkö tämä toteuttaa myöhemmin
					if (args.size() % 2 != 0) illegalArguments("illegal arguments for '~=': even number required (got " + (args.size()-1) + ")");
					String text = rval.str();
					try {
						for (int j = 0; j < args.size(); j+=2) {
							checkString(e.asString() + "~=", args.get(j));
							checkString(e.asString() + "~=", args.get(j+1));
							Pattern pattern = args.get(j).pattern();
							String replacement = args.get(j+1).str();
							if (quoteMode) replacement = Matcher
									.quoteReplacement(replacement);
							text = pattern.matcher(text).replaceAll(replacement);
						}
					} catch (PatternSyntaxException ex) {
						error("'"+e.asString()+"~=': pattern syntax exception: "
								+ ex.getMessage());
					}
					assign.accept(RödaString.of(text));
					return;
				};
			} break;
			case "?": {
				r = () -> {
					if (e.type != Expression.Type.VARIABLE)
						error("bad lvalue for '?': " + e.asString());
					_out.push(RödaBoolean.of(scope.resolve(e.variable) != null));
				};
			} break;
			default:
				unknownName("unknown operator " + cmd.operator);
				r = null;
			}
			Runnable finalR = () -> {
				if (enableDebug) {
					callStack.get().push("variable command " + e.asString() + " " + cmd.operator + " "
						+ args.stream()
							.map(RödaValue::str)
							.collect(joining(" "))
						+ "\n\tat " + cmd.file + ":" + cmd.line);
				}
				try {
					r.run();
				}
				catch (RödaException ex) { throw ex; }
				catch (Throwable ex) { error(ex); }
				finally {
					if (enableDebug) callStack.get().pop();
				}
			};
			return finalR;
		}

		if (cmd.type == Command.Type.WHILE || cmd.type == Command.Type.IF) {
			boolean isWhile = cmd.type == Command.Type.WHILE;
			boolean neg = cmd.negation;
			String commandName = isWhile?(neg?"until":"while"):(neg?"unless":"if");
			Runnable r = () -> {
				boolean goToElse = true;
				do {
					RödaScope newScope = new RödaScope(this, scope);
					if (evalCond(commandName, cmd.cond, scope, _in) ^ neg) break;
					goToElse = false;
					try {
						for (Statement s : cmd.body) {
							evalStatement(s, newScope, _in, _out, false);
						}
					} catch (BreakOrContinueException e) {
						if (!isWhile) throw e;
						if (e.isBreak) break;
					}
				} while (isWhile);
				if (goToElse && cmd.elseBody != null) {
					RödaScope newScope = new RödaScope(this, scope);
					for (Statement s : cmd.elseBody) {
						evalStatement(s, newScope, _in, _out, false);
					}
				}
			};
			return r;
		}

		if (cmd.type == Command.Type.FOR) {
			Runnable r;
			if (cmd.list != null) {
				if (cmd.variables.size() != 1) error("invalid for statement: there must be only 1 variable when iterating a list");
				RödaValue list = evalExpression(cmd.list, scope, in, out).impliciteResolve();
				checkList("for", list);
				r = () -> {
					for (RödaValue val : list.list()) {
						RödaScope newScope = new RödaScope(this, scope);
						newScope.setLocal(cmd.variables.get(0), val);
						if (cmd.cond != null && evalCond("for if", cmd.cond, newScope, _in))
							continue;
						try {
							for (Statement s : cmd.body) {
								evalStatement(s, newScope, _in, _out, false);
							}
						} catch (BreakOrContinueException e) {
							if (e.isBreak) break;
						}
					}
				};
			} else {
				r = () -> {
					String firstVar = cmd.variables.get(0);
					List<String> otherVars = cmd.variables.subList(1, cmd.variables.size());
					while (true) {
						RödaValue val = _in.pull();
						if (val == null) break;

						RödaScope newScope = new RödaScope(this, scope);
						newScope.setLocal(firstVar, val);
						for (String var : otherVars) {
							val = _in.pull();

							if (val == null) {
								String place = "for loop: "
										+ "for " + cmd.variables.stream().collect(joining(", "))
										+ (cmd.list != null ? " in " + cmd.list.asString() : "")
										+ " at " + cmd.file + ":" + cmd.line;
								emptyStream("empty stream (in " + place + ")");
							}
							newScope.setLocal(var, val);
						}

						if (cmd.cond != null
								&& evalCond("for if", cmd.cond, newScope, _in))
							continue;
						try {
							for (Statement s : cmd.body) {
								evalStatement(s, newScope, _in, _out, false);
							}
						} catch (BreakOrContinueException e) {
							if (e.isBreak) break;
						}
					}
				};
			}
			return r;
		}

		if (cmd.type == Command.Type.TRY_DO) {
			Runnable r = () -> {
				try {
					RödaScope newScope = new RödaScope(this, scope);
					for (Statement s : cmd.body) {
						evalStatement(s, newScope, _in, _out, false);
					}
				} catch (ReturnException e) {
					throw e;
				} catch (BreakOrContinueException e) {
					throw e;
				} catch (Exception e) {
					if (cmd.variable != null) {
						RödaScope newScope = new RödaScope(this, scope);
						RödaValue errorObject;
						if (e instanceof RödaException)
							errorObject = ((RödaException) e).getErrorObject();
						else errorObject = makeErrorObject(javaErrorRecord, e.getClass().getName() + ": "
								+ e.getMessage(),
								e.getStackTrace());
						newScope.setLocal(cmd.variable, errorObject);
						for (Statement s : cmd.elseBody) {
							evalStatement(s, newScope, _in, _out, false);
						}
					}
				}
			};
			return r;
		}

		if (cmd.type == Command.Type.TRY) {
			Runnable tr = evalCommand(cmd.cmd, scope, in, out, _in, _out);
			Runnable r = () -> {
				try {
					tr.run();
				} catch (ReturnException e) {
					throw e;
				} catch (BreakOrContinueException e) {
					throw e;
				} catch (Exception e) {} // virheet ohitetaan TODO virheenkäsittely
			};
			return r;
		}

		if (cmd.type == Command.Type.RETURN) {
			if (!cmd.arguments.kwarguments.isEmpty())
				illegalArguments("all arguments of return must be non-kw");
			List<RödaValue> args = flattenArguments(cmd.arguments.arguments, scope, in, out, true);
			Runnable r = () -> {
				for (RödaValue arg : args) out.push(arg);
				throw RETURN_EXCEPTION;
			};
			return r;
		}

		if (cmd.type == Command.Type.BREAK
				|| cmd.type == Command.Type.CONTINUE) {
			Runnable r = () -> {
				throw cmd.type == Command.Type.BREAK ? BREAK_EXCEPTION : CONTINUE_EXCEPTION;
			};
			return r;
		}

		if (cmd.type == Command.Type.EXPRESSION) {
			return () -> {
				_out.push(evalExpression(cmd.name, scope, _in, _out));
			};
		}

		unknownName("unknown command");
		return null;
	}

	private boolean evalCond(String cmd, Statement cond, RödaScope scope, RödaStream in) {
		RödaStream condOut = RödaStream.makeStream();
		evalStatement(cond, scope, in, condOut, true);
		boolean brk = false;
		while (true) {
			RödaValue val = condOut.pull();
			if (val == null) break;
			checkBoolean(cmd, val);
			brk = brk || !val.bool();
		}
		return brk;
	}

	private RödaValue evalExpression(Expression exp, RödaScope scope, RödaStream in, RödaStream out) {
		return evalExpressionWithoutErrorHandling(exp, scope, in, out, false);
	}

	private RödaValue evalExpression(Expression exp, RödaScope scope, RödaStream in, RödaStream out,
			boolean variablesAreReferences) {
		if (enableDebug) callStack.get().push("expression " + exp.asString() + "\n\tat " + exp.file + ":" + exp.line);
		RödaValue value;
		try {
			value = evalExpressionWithoutErrorHandling(exp, scope, in, out,
					variablesAreReferences);
		}
		catch (RödaException e) { throw e; }
		catch (ReturnException e) { throw e; }
		catch (Throwable e) { error(e); value = null; }
		finally {
			if (enableDebug) callStack.get().pop();
		}
		return value;
	}

	@SuppressWarnings("incomplete-switch")
	private RödaValue evalExpressionWithoutErrorHandling(Expression exp, RödaScope scope,
			RödaStream in, RödaStream out,
			boolean variablesAreReferences) {
		if (exp.type == Expression.Type.STRING) return RödaString.of(exp.string);
		if (exp.type == Expression.Type.PATTERN) return RödaString.of(exp.pattern);
		if (exp.type == Expression.Type.INTEGER) return RödaInteger.of(exp.integer);
		if (exp.type == Expression.Type.FLOATING) return RödaFloating.of(exp.floating);
		if (exp.type == Expression.Type.BLOCK) return RödaFunction.of(exp.block, scope);
		if (exp.type == Expression.Type.LIST) return RödaList.of(exp.list
				.stream()
				.map(e -> evalExpression(e, scope, in, out).impliciteResolve())
				.collect(toList()));
		if (exp.type == Expression.Type.REFLECT
				|| exp.type == Expression.Type.TYPEOF) {
			Datatype type;
			if (exp.type == Expression.Type.REFLECT) {
				type = scope.substitute(exp.datatype);
			}
			else { // TYPEOF
				RödaValue value = evalExpression(exp.sub, scope, in, out).impliciteResolve();
				type = value.basicIdentity();
			}
			if (!scope.getRecordDeclarations().containsKey(type.name))
				unknownName("reflect: unknown record class '" + type + "'");
			return scope.getRecordDeclarations().get(type.name).reflection;
		}
		if (exp.type == Expression.Type.NEW) {
			Datatype type = scope.substitute(exp.datatype);
			List<Datatype> subtypes = exp.datatype.subtypes.stream()
					.map(scope::substitute).collect(toList());
			List<RödaValue> args = exp.list.stream()
					.map(e -> evalExpression(e, scope, in, out))
					.map(RödaValue::impliciteResolve)
					.collect(toList());
			return newRecord(type, subtypes, args, scope);
		}
		if (exp.type == Expression.Type.LENGTH
				|| exp.type == Expression.Type.ELEMENT
				|| exp.type == Expression.Type.SLICE
				|| exp.type == Expression.Type.CONTAINS) {
			RödaValue list = evalExpression(exp.sub, scope, in, out).impliciteResolve();

			if (exp.type == Expression.Type.LENGTH) {
				return list.length();
			}

			if (exp.type == Expression.Type.ELEMENT) {
				RödaValue index = evalExpression(exp.index, scope, in, out).impliciteResolve();
				return list.get(index);
			}

			if (exp.type == Expression.Type.SLICE) {
				RödaValue start, end;

				if (exp.index1 != null)
					start = evalExpression(exp.index1, scope, in, out)
					.impliciteResolve();
				else start = null;

				if (exp.index2 != null)
					end = evalExpression(exp.index2, scope, in, out)
					.impliciteResolve();
				else end = null;

				return list.slice(start, end);
			}

			if (exp.type == Expression.Type.CONTAINS) {
				RödaValue index = evalExpression(exp.index, scope, in, out).impliciteResolve();
				return list.contains(index);
			}
		}
		if (exp.type == Expression.Type.FIELD) {
			return evalExpression(exp.sub, scope, in, out).impliciteResolve()
					.getField(exp.field);
		}
		if (exp.type == Expression.Type.CONCAT) {
			RödaValue val1 = evalExpression(exp.exprA, scope, in, out).impliciteResolve();
			RödaValue val2 = evalExpression(exp.exprB, scope, in, out).impliciteResolve();
			if (val1.is(LIST) && val2.is(LIST)) {
				List<RödaValue> newList = new ArrayList<>();
				newList.addAll(val1.list());
				newList.addAll(val2.list());
				return RödaList.of(newList);
			}
			else return RödaString.of(val1.str() + val2.str());
		}
		if (exp.type == Expression.Type.CONCAT_CHILDREN) {
			RödaValue val1 = evalExpression(exp.exprA, scope, in, out).impliciteResolve();
			RödaValue val2 = evalExpression(exp.exprB, scope, in, out).impliciteResolve();
			return concat(val1, val2);
		}
		if (exp.type == Expression.Type.JOIN) {
			RödaValue list = evalExpression(exp.exprA, scope, in, out).impliciteResolve();
			RödaValue separator = evalExpression(exp.exprB, scope, in, out).impliciteResolve();
			return list.join(separator);
		}
		if (exp.type == Expression.Type.IS) {
			Datatype type = scope.substitute(exp.datatype);
			RödaValue value = evalExpression(exp.sub, scope, in, out).impliciteResolve();
			return RödaBoolean.of(value.is(type));
		}
		if (exp.type == Expression.Type.IN) {
			RödaValue value = evalExpression(exp.exprA, scope, in, out).impliciteResolve();
			RödaValue list = evalExpression(exp.exprB, scope, in, out).impliciteResolve();
			return list.containsValue(value);
		}
		if (exp.type == Expression.Type.STATEMENT_LIST) {
			RödaStream _out = RödaStream.makeStream();
			evalStatement(exp.statement, scope, in, _out, true);
			RödaValue val = _out.readAll();
			if (val == null)
				emptyStream("empty stream (in statement expression: " + exp.asString() + ")");
			return val;
		}
		if (exp.type == Expression.Type.STATEMENT_SINGLE) {
			RödaStream _out = RödaStream.makeStream();
			evalStatement(exp.statement, scope, in, _out, true);
			RödaValue val = _out.readAll();
			if (val.list().isEmpty())
				emptyStream("empty stream (in statement expression: " + exp.asString() + ")");
			if (val.list().size() > 1)
				fullStream("stream is full (in statement expression: " + exp.asString() + ")");
			return val.list().get(0);
		}
		if (exp.type == Expression.Type.VARIABLE) {
			if (variablesAreReferences) {
				return RödaReference.of(exp.variable, scope);
			}
			RödaValue v = scope.resolve(exp.variable);
			if (v == null) unknownName("variable '" + exp.variable + "' not found");
			return v;
		}
		if (exp.type == Expression.Type.CALCULATOR) {
			if (exp.isUnary) {
				RödaValue sub = evalExpression(exp.sub, scope, in, out).impliciteResolve();
				switch (exp.ctype) {
				case NOT:
					if (!sub.is(BOOLEAN)) typeMismatch("tried to NOT " + sub.typeString());
					return RödaBoolean.of(!sub.bool());
				case NEG:
					if (!sub.is(INTEGER)) typeMismatch("tried to NEG " + sub.typeString());
					return RödaInteger.of(-sub.integer());
				case BNOT:
					if (!sub.is(INTEGER)) typeMismatch("tried to BNOT " + sub.typeString());
					return RödaInteger.of(~sub.integer());
				}
			}
			else {
				RödaValue val1 = evalExpression(exp.exprA, scope, in, out).impliciteResolve();
				RödaValue val2 = null;
				Supplier<RödaValue> getVal2 = () -> evalExpression(exp.exprB, scope, in, out)
						.impliciteResolve();
				switch (exp.ctype) {
				case AND:
					if (!val1.is(BOOLEAN)) typeMismatch("tried to AND " + val1.typeString());
					if (val1.bool() == false) return RödaBoolean.of(false);
					val2 = getVal2.get();
					if (!val2.is(BOOLEAN)) typeMismatch("tried to AND " + val2.typeString());
					return RödaBoolean.of(val2.bool());
				case OR:
					if (!val1.is(BOOLEAN)) typeMismatch("tried to OR " + val1.typeString());
					if (val1.bool() == true) return RödaBoolean.of(true);
					val2 = getVal2.get();
					if (!val2.is(BOOLEAN)) typeMismatch("tried to OR " + val2.typeString());
					return RödaBoolean.of(val1.bool() || val2.bool());
				case XOR:
					if (!val1.is(BOOLEAN)) typeMismatch("tried to XOR " + val1.typeString());
					val2 = getVal2.get();
					if (!val2.is(BOOLEAN)) typeMismatch("tried to XOR " + val2.typeString());
					return RödaBoolean.of(val1.bool() ^ val2.bool());
				}
				val2 = getVal2.get();
				return val1.callOperator(exp.ctype, val2);
			}
			unknownName("unknown expression type " + exp.ctype);
			return null;
		}

		unknownName("unknown expression type " + exp.type);
		return null;
	}
	
	private RödaValue newRecord(Datatype type, List<Datatype> subtypes, List<RödaValue> args, RödaScope scope) {
		switch (type.name) {
		case "list":
			if (subtypes.size() == 0)
				return RödaList.empty();
			else if (subtypes.size() == 1)
				return RödaList.empty(subtypes.get(0));
			illegalArguments("wrong number of typearguments to 'list': 1 required, got " + subtypes.size());
			return null;
		case "map":
			if (subtypes.size() == 0)
				return RödaMap.empty();
			else if (subtypes.size() == 1)
				return RödaMap.empty(subtypes.get(0));
			illegalArguments("wrong number of typearguments to 'map': 1 required, got " + subtypes.size());
			return null;
		}
		return newRecord(null, type, subtypes, args, scope);
	}

	private RödaValue newRecord(RödaValue value,
			Datatype type, List<Datatype> subtypes, List<RödaValue> args, RödaScope scope) {
		Map<String, RecordDeclaration> records = scope.getRecordDeclarations();
		Record r = records.get(type.name).tree;
		RödaScope declarationScope = records.get(type.name).declarationScope;
		if (r == null)
			unknownName("record class '" + type.name + "' not found");
		if (r.typeparams.size() != subtypes.size())
			illegalArguments("wrong number of typearguments for '" + r.name + "': "
					+ r.typeparams.size() + " required, got " + subtypes.size());
		if (r.params.size() != args.size())
			illegalArguments("wrong number of arguments for '" + r.name + "': "
					+ r.params.size() + " required, got " + args.size());
		value = value != null ? value : RödaRecordInstance.of(r, subtypes, scope.getRecords());
		RödaScope recordScope = new RödaScope(this, declarationScope);
		recordScope.setLocal("self", value);
		for (int i = 0; i < subtypes.size(); i++) {
			recordScope.addTypearg(r.typeparams.get(i), subtypes.get(i));
		}
		for (int i = 0; i < args.size(); i++) {
			recordScope.setLocal(r.params.get(i), args.get(i));
		}
		for (Record.SuperExpression superExp : r.superTypes) {
			Datatype superType = scope.substitute(superExp.type);
			List<Datatype> superSubtypes = superExp.type.subtypes.stream()
					.map(recordScope::substitute).collect(toList());
			List<RödaValue> superArgs = superExp.args.stream()
					.map(e -> evalExpression(e, recordScope,
							RödaStream.makeEmptyStream(), RödaStream.makeEmptyStream()))
					.map(RödaValue::impliciteResolve)
					.collect(toList());
			newRecord(value, superType, superSubtypes, superArgs, scope);
		}
		for (Record.Field f : r.fields) {
			if (f.defaultValue != null) {
				value.setField(f.name, evalExpression(f.defaultValue, recordScope,
						RödaStream.makeEmptyStream(), RödaStream.makeEmptyStream(), false));
			}
		}
		return value;
	}

	private RödaValue concat(RödaValue val1, RödaValue val2) {
		if (val1.is(LIST) && val2.is(LIST)) {
			List<RödaValue> newList = new ArrayList<>();
			for (RödaValue valA : val1.list()) {
				for (RödaValue valB : val2.list()) {
					newList.add(concat(valA, valB));
				}
			}
			return RödaList.of(newList);
		}
		if (val1.is(LIST)) {
			List<RödaValue> newList = new ArrayList<>();
			for (RödaValue val : val1.list()) {
				newList.add(concat(val, val2));
			}
			return RödaList.of(newList);
		}
		if (val2.is(LIST)) {
			List<RödaValue> newList = new ArrayList<>();
			for (RödaValue val : val2.list()) {
				newList.add(concat(val1, val));
			}
			return RödaList.of(newList);
		}
		return RödaString.of(val1.str()+val2.str());
	}
}
