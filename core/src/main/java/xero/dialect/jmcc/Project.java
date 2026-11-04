package xero.dialect.jmcc;

import xero.compile.Compiler;
import xero.lang.Ast;
import xero.lang.Diagnostic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class Project {
    private static final String MAIN = "";
    private static final String EXTENSION = ".jc";

    final Table table;
    final Map<String, Set<String>> declared = new HashMap<>();
    final Map<String, Set<String>> used = new HashMap<>();
    final Set<String> written = new HashSet<>();
    final Map<String, Tree.Func> functions = new LinkedHashMap<>();
    final Map<String, String> aliases = new HashMap<>();
    final Set<String> foreign = new HashSet<>();
    final Map<String, List<String>> enums = new HashMap<>();
    final Map<String, Tree.Expr> inlines = new HashMap<>();
    final Map<String, String> types = new HashMap<>();
    final Map<String, String> kinds = new HashMap<>();
    final Set<String> typed = new HashSet<>();
    final Map<String, String> shapes = new HashMap<>();
    final Map<String, Set<String>> assignedParams = new HashMap<>();
    final Map<String, Set<String>> variableParams = new HashMap<>();
    final Map<String, Set<String>> valueParams = new HashMap<>();

    private final Compiler.Loader loader;
    private final Map<String, Tree.Unit> units = new LinkedHashMap<>();
    private final Map<String, List<Diagnostic>> diagnostics = new HashMap<>();
    private final Map<String, Ast.File> lowered = new HashMap<>();
    private final Map<String, String> texts = new HashMap<>();
    private final Map<String, String> paths = new HashMap<>();

    Project(Table table, String file, String text, Compiler.Loader loader) {
        this.table = table;
        this.loader = loader;
        read(file, text);
        for (Tree.Unit u : units.values()) register(u);
        for (Tree.Unit u : units.values()) new Lower(this, new ArrayList<>(), false, u.file()).unit(u);
        for (Tree.Unit u : units.values()) {
            List<Diagnostic> out = diagnostics.get(key(u.file()));
            lowered.put(key(u.file()), new Lower(this, out, true, u.file()).unit(u));
        }
    }

    private static String key(String file) { return file == null ? MAIN : file; }

    private void read(String file, String text) {
        if (units.containsKey(key(file))) return;
        List<Diagnostic> out = new ArrayList<>();
        diagnostics.put(key(file), out);
        Tree.Unit unit = Read.parse(file, text, out);
        units.put(key(file), unit);
        for (Tree.Stmt s : unit.items()) {
            if (!(s instanceof Tree.Import imp)) continue;
            String path = path(file, imp.path());
            paths.put(key(file) + "\u0000" + imp.path(), path);
            if (!source(path)) continue;
            String resolved = resolve(file, path);
            if (units.containsKey(resolved)) continue;
            String loaded = load(resolved);
            if (loaded != null) read(resolved, loaded);
        }
    }

    private String load(String path) {
        if (texts.containsKey(path)) return texts.get(path);
        if (loader == null) return null;
        String loaded = loader.load(path);
        texts.put(path, loaded);
        return loaded;
    }

    String importPath(String file, String raw) { return paths.getOrDefault(key(file) + "\u0000" + raw, raw); }

    private String path(String file, String raw) {
        String p = raw.strip();
        if (source(p)) return p;
        boolean relative = p.startsWith(".") || p.indexOf('/') >= 0 || p.indexOf('\\') >= 0;
        if (relative) return p + EXTENSION;
        return load(resolve(file, p + EXTENSION)) != null ? p + EXTENSION : p;
    }

    static boolean source(String path) { return path.toLowerCase(Locale.ROOT).endsWith(EXTENSION); }

    private static String resolve(String from, String path) {
        String p = path.replace('\\', '/');
        String base = from == null ? "" : from.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        String dir = p.startsWith("/") || slash < 0 ? "" : base.substring(0, slash + 1);
        List<String> parts = new ArrayList<>();
        for (String part : (dir + p).split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..") && !parts.isEmpty() && !parts.get(parts.size() - 1).equals("..")) parts.remove(parts.size() - 1);
            else parts.add(part);
        }
        String joined = String.join("/", parts);
        return (dir + p).startsWith("/") ? "/" + joined : joined;
    }

    private void register(Tree.Unit u) {
        for (Tree.Named n : u.declared()) declared.computeIfAbsent(n.name(), k -> new LinkedHashSet<>()).add(n.scope());
        for (Tree.Func f : u.functions()) {
            functions.putIfAbsent(f.name(), f);
            marks(f.name(), f.marks());
        }
        for (Tree.Enum e : u.enums()) {
            enums.put(e.name(), e.members());
            marks(e.name(), e.marks());
        }
        for (Tree.Foreign f : u.foreign()) foreign.add(f.name());
        for (Tree.Alias a : u.aliases()) types.put(a.name(), a.target());
        for (Tree.Stmt s : u.items()) {
            if (s instanceof Tree.Assign a && inline(a) != null) inlines.put(inline(a).name(), a.values().get(0));
            if (!(s instanceof Tree.Var v) || !"inline".equals(v.scope()) || v.values().size() != v.targets().size()) continue;
            for (int i = 0; i < v.targets().size(); i++)
                if (v.targets().get(i).target() instanceof Tree.Name n) inlines.put(n.name(), v.values().get(i));
        }
    }

    static Tree.Name inline(Tree.Assign a) {
        boolean one = a.targets().size() == 1 && a.values().size() == 1 && a.op().equals("=");
        return one && a.targets().get(0).target() instanceof Tree.Name n && "inline".equals(n.scope()) && n.parts() == null ? n : null;
    }

    private void marks(String name, List<Tree.Mark> marks) {
        for (Tree.Mark m : marks) {
            if (!m.name().equals("alias") && !m.name().equals("алиас")) continue;
            for (Tree.Arg a : m.args()) if (a.value() instanceof Tree.Str s && s.parts() == null) aliases.put(s.text(), name);
        }
    }

    boolean pinned(String name) {
        Set<String> scopes = used.get(name);
        return scopes != null && scopes.size() > 1;
    }

    String scopeOf(String name) {
        Set<String> scopes = declared.get(name);
        return scopes == null || scopes.isEmpty() ? null : scopes.iterator().next();
    }

    Compiler.Front front() {
        return new Compiler.Front() {
            @Override
            public Ast.File parse(String file, String text, List<Diagnostic> out) {
                List<Diagnostic> own = diagnostics.get(key(file));
                if (own != null) for (Diagnostic d : own) out.add(d);
                Ast.File f = lowered.get(key(file));
                return f != null ? f : new Ast.File(List.of());
            }

            @Override
            public boolean source(String path) { return Project.source(path); }
        };
    }

    Compiler.Loader loader() { return path -> texts.containsKey(path) ? texts.get(path) : loader == null ? null : loader.load(path); }
}
