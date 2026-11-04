package xero.dialect;

import xero.ide.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class Dialects {
    private static List<Dialect> all;

    private Dialects() {}

    public static synchronized List<Dialect> all() {
        if (all != null) return all;
        Service service = Service.load();
        List<Dialect> out = new ArrayList<>();
        out.add(new XeroDialect(service));
        out.add(new xero.dialect.jmcc.JmccDialect(service));
        out.add(new xero.dialect.jmscript.JmscriptDialect(service));
        out.add(new xero.dialect.justice.JusticeDialect(service));
        out.add(new JsonDialect());
        all = Collections.unmodifiableList(out);
        return all;
    }

    public static Dialect byId(String id) {
        for (Dialect d : all()) if (d.id().equals(id)) return d;
        return null;
    }

    public static Dialect forFile(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (Dialect d : all()) if (lower.endsWith("." + d.extension())) return d;
        return null;
    }
}
