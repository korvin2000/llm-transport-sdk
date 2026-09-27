package net.ai.gate.vendors.openai.internal;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import net.ai.gate.json.JsonArray;
import net.ai.gate.json.JsonObject;
import net.ai.gate.model.Model;
import net.ai.gate.spi.catalog.ModelSource;
import net.ai.gate.spi.catalog.ProviderHttp;

/// The `GET models` listing shared by OpenAI and compatible servers: `{"data": [{"id": …}]}`. It proves
/// existence only; limits and prices come from the catalog.
public enum ModelList implements ModelSource {
    INSTANCE;

    @Override public List<Model> fetch(ProviderHttp http) throws IOException {
        var models = new ArrayList<Model>();
        if (http.get("models") instanceof JsonObject body && body.get("data").orElse(null) instanceof JsonArray data)
            for (var entry : data.values())
                if (entry instanceof JsonObject o) models.add(Model.builder(http.provider().id(), o.string("id")).build());
        return models;
    }
}
