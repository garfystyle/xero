package xero.js;

import org.teavm.extension.spi.resources.ResourcesPolicy;

import java.util.Collection;

public class Resources implements ResourcesPolicy {
    private static final String[] DATA = {
            "xero/data/mapping.json", "xero/data/catalog.json", "xero/data/values.json",
            "xero/data/pickers.json", "xero/data/placeholders.json", "xero/data/minecraft.json",
            "xero/data/components.json"};

    @Override
    public String[] supplyResources(Collection<? extends String> classNames) {
        return DATA.clone();
    }
}
