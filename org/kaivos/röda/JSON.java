package org.kaivos.röda;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Stack;
import java.util.Iterator;
import java.util.Collections;
import java.util.NoSuchElementException;

import java.util.regex.Pattern;

import org.kaivos.nept.parser.TokenScanner;
import org.kaivos.nept.parser.TokenList;
import org.kaivos.nept.parser.ParsingException;

public class JSON {
	private JSON() {}

	private static final String NUMBER_REGEX = "-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE](\\+|-)?[0-9]+)";
	
	public static final TokenScanner t = new TokenScanner()
		.addOperators("[]{},:.")
		.addOperatorRule("true")
		.addOperatorRule("false")
		.addOperatorRule("null")
		.addPatternRule(Pattern.compile(NUMBER_REGEX))
		.separateIdentifiersAndPunctuation(false)
		.addStringRule('"','"','\\')
		.addEscapeCode('\\', "\\")
		.addEscapeCode('n', "\n")
		.addEscapeCode('r', "\r")
		.addEscapeCode('t', "\t")
		.addCharacterEscapeCode('u', 4, 16)
		.appendOnEOF("<EOF>");

	public static String escape(String string) {
		string = string.replaceAll("\\\\" , "\\\\\\\\").replaceAll("\"" , "\\\\\"");
		return "\"" + string + "\"";
	}


	/*** Luokat ***/
	
	public static interface JSONKey {}
	public static class JSONKeyString implements JSONKey {
		private final String key;
		private JSONKeyString(String key) {
			this.key = key;
		}

		public String getKey() {
			return key;
		}

		@Override
		public String toString() {
			return escape(key);
		}

		@Override
		public int hashCode() {
			return key.hashCode();
		}
	}
	public static class JSONKeyInteger implements JSONKey {
		private final int key;
		private JSONKeyInteger(int key) {
			this.key = key;
		}

		public int getKey() {
			return key;
		}

		@Override
		public String toString() {
			return String.valueOf(key);
		}

		@Override
		public int hashCode() {
			return key;
		}
	}
	
	public static abstract class JSONElement implements Iterable<JSONElement> {
		private final List<JSONKey> path;
		private JSONElement(List<JSONKey> path) {
			this.path = path;
		}

		public List<JSONKey> getPath() {
			return path;
		}
	}
	
	public static class JSONList extends JSONElement {
		private final List<JSONElement> elements;
		private JSONList(List<JSONKey> path, List<JSONElement> elements) {
			super(path);
			this.elements = Collections.unmodifiableList(elements);
		}

		public List<JSONElement> getElements() {
			return elements;
		}

		@Override
		public String toString() {
			String ans = "[";
			int i = 0;
			for (JSONElement e : elements) {
				if (i != 0) ans += ",";
				ans += e.toString();
				i++;
			}
			ans += "]";
			return ans;
		}

		@Override
		public Iterator<JSONElement> iterator() {
			return new Iterator<JSONElement>() {
				int i = -1;
				Iterator<JSONElement> eIterator;

				@Override
				public boolean hasNext() {
					return i < 0 || eIterator != null;
				}
				
				@Override
				public JSONElement next() {
					if (!hasNext()) throw new NoSuchElementException();
					if (i == -1) {
						updateIterator();
						return JSONList.this;
					}
					JSONElement a = eIterator.next();
					updateIterator();
					return a;
				}
				private void updateIterator() {
					while (eIterator == null || !eIterator.hasNext()) {
						if (++i < elements.size()) {
							eIterator = elements.get(i).iterator();
						}
						else {
							eIterator = null;
							break;
						}
					}
				}
			};
		}
	}
	
	public static class JSONMap extends JSONElement {
		private final Map<JSONKey, JSONElement> elements;
		private JSONMap(List<JSONKey> path, Map<JSONKey, JSONElement> elements) {
			super(path);
			this.elements = Collections.unmodifiableMap(elements);
		}

		public Map<JSONKey, JSONElement> getElements() {
			return elements;
		}

		@Override
		public String toString() {
			String ans = "{";
			int i = 0;
			for (Map.Entry<JSONKey, JSONElement> entry : elements.entrySet()) {
				if (i != 0) ans += ",";
				ans += entry.getKey().toString();
				ans += ":";
				ans += entry.getValue().toString();
				i++;
			}
			ans += "}";
			return ans;
		}

		@Override
		public Iterator<JSONElement> iterator() {
			return new Iterator<JSONElement>() {
				int i = -1;
				Iterator<JSONElement> eIterator;
				List<JSONElement> elements = new ArrayList<>(JSONMap.this.elements.values());

				@Override
				public boolean hasNext() {
					return i < 0 || eIterator != null;
				}
				
				@Override
				public JSONElement next() {
					if (!hasNext()) throw new NoSuchElementException();
					if (i == -1) {
						updateIterator();
						return JSONMap.this;
					}
					JSONElement a = eIterator.next();
					updateIterator();
					return a;
				}
				private void updateIterator() {
					while (eIterator == null || !eIterator.hasNext()) {
						if (++i < elements.size()) {
							eIterator = elements.get(i).iterator();
						}
						else {
							eIterator = null;
							break;
						}
					}
				}
			};
		}
	}

	private static abstract class JSONAtomic<T> extends JSONElement {
		protected final T value;
		private JSONAtomic(List<JSONKey> path, T value) {
			super(path);
			this.value = value;
		}

		public T getValue() {
			return value;
		}

		@Override
		public String toString() {
			return value.toString();
		}

		@Override
		public Iterator<JSONElement> iterator() {
			return new Iterator<JSONElement>() {
				boolean first = true;

				@Override
				public boolean hasNext() {
					return first;
				}

				@Override
				public JSONElement next() {
					if (first) {
						first = false;
						return JSONAtomic.this;
					}
					throw new NoSuchElementException();
				}
			};
		}
	}
	
	public static class JSONString extends JSONAtomic<String> {
		private JSONString(List<JSONKey> path, String value) {
			super(path, value);
		}

		@Override
		public String toString() {
			return escape(value);
		}
	}
	public static class JSONInteger extends JSONAtomic<Integer> {
		private JSONInteger(List<JSONKey> path, int value) {
			super(path, value);
		}
	}
	public static class JSONDouble extends JSONAtomic<Double> {
		private JSONDouble(List<JSONKey> path, double value) {
			super(path, value);
		}
	}
	
	public static enum JSONConstants {
		TRUE("true"),
		FALSE("false"),
		NULL("null");

		private String name;

		JSONConstants(String name) {
			this.name = name;
		}

		public String getName() {
			return name;
		}
	}

	public static class JSONConstant extends JSONAtomic<JSONConstants> {
		private JSONConstant(List<JSONKey> path, JSONConstants value) {
			super(path, value);
		}

		@Override
		public String toString() {
			return value.getName();
		}
	}

	/*** Parseri ***/

	public static JSONElement parseJSON(String text) {
		return parse(t.tokenize(text, "<input>"), new Stack<>());
	}

	private static JSONElement parse(TokenList tl, Stack<JSONKey> path) {
		if (tl.isNext("[")) {
			return parseList(tl, path);
		}
		if (tl.isNext("{")) {
			return parseMap(tl, path);
		}
		if (tl.isNext("\"")) {
			tl.accept("\"");
			String text = tl.nextString();
			tl.accept("\"");
			return new JSONString(new ArrayList<>(path), text);
		}
		if (tl.seekString().matches("[0-9]+")) {
			int integer = Integer.parseInt(tl.nextString());
			return new JSONInteger(new ArrayList<>(path), integer);
		}
		if (tl.seekString().matches(NUMBER_REGEX)) {
			double doubling = Double.parseDouble(tl.nextString());
			return new JSONDouble(new ArrayList<>(path), doubling);
		}
		for (JSONConstants constant : JSONConstants.values()) {
			if (tl.seekString().equals(constant.getName())) {
				tl.next();
				return new JSONConstant(new ArrayList<>(path), constant);
			}
		}
		throw new ParsingException(TokenList.expected("[", "{", "true", "false", "null", "<number>", "<string>"), tl.next());
	}

	private static JSONList parseList(TokenList tl, Stack<JSONKey> path) {
		List<JSONElement> list = new ArrayList<>();
		tl.accept("[");
		int i = 0;
		while (!tl.isNext("]")) {
			if (i != 0) tl.accept(",");
			path.push(new JSONKeyInteger(i));
			list.add(parse(tl, path));
			path.pop();
			i++;
		}
		tl.accept("]");
		return new JSONList(new ArrayList<>(path), list);
	}

	private static JSONMap parseMap(TokenList tl, Stack<JSONKey> path) {
		Map<JSONKey, JSONElement> map = new HashMap<>();
		tl.accept("{");
		int i = 0;
		while (!tl.isNext("}")) {
			if (i != 0) tl.accept(",");
			tl.accept("\"");
			JSONKey key = new JSONKeyString(tl.nextString());
			tl.accept("\"");
			tl.accept(":");
			path.push(key);
			map.put(key, parse(tl, path));
			path.pop();
			i++;
		}
		tl.accept("}");
		return new JSONMap(new ArrayList<>(path), map);
	}
}
