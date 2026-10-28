package io.github.jichuo1.meguviz.android.runtime.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Explicit immutable provider snapshot. No discovery, fallback construction or hot-path search. */
public final class AdapterRegistry<T> {
    public static final int SPI_MAJOR = 1;
    public static final int SPI_MINOR = 0;
    private final Map<String, Provider<T>> providers;
    private final Map<String, Provider<T>> selected;
    private long candidateChecks;

    public AdapterRegistry(Collection<Provider<T>> providers, Set<String> grantedPermissions,
                           Set<String> environmentCapabilities, Map<String, String> overrides) {
        Objects.requireNonNull(providers); Objects.requireNonNull(grantedPermissions);
        Objects.requireNonNull(environmentCapabilities); Objects.requireNonNull(overrides);
        Map<String, Provider<T>> byId = new LinkedHashMap<>();
        Set<String> available = new HashSet<>(environmentCapabilities);
        for (Provider<T> provider : providers) {
            Objects.requireNonNull(provider);
            Descriptor descriptor = provider.descriptor;
            if (byId.put(descriptor.id, provider) != null) throw new IllegalArgumentException("Duplicate provider: " + descriptor.id);
            if (descriptor.spiMajor != SPI_MAJOR || descriptor.minimumSpiMinor > SPI_MINOR)
                throw new IllegalArgumentException("Unsupported SPI: " + descriptor.id);
            if (!grantedPermissions.containsAll(descriptor.permissions))
                throw new IllegalArgumentException("Permission escalation: " + descriptor.id);
            available.addAll(descriptor.provides);
        }
        for (Provider<T> provider : byId.values()) {
            Descriptor descriptor = provider.descriptor;
            if (!available.containsAll(descriptor.requires)) throw new IllegalArgumentException("Missing capabilities: " + descriptor.id);
            if (!byId.keySet().containsAll(descriptor.dependencies)) throw new IllegalArgumentException("Missing dependency: " + descriptor.id);
        }
        Set<String> visited = new HashSet<>();
        for (String id : byId.keySet()) visit(id, byId, visited, new HashSet<>());
        Map<String, List<Provider<T>>> choices = new HashMap<>();
        for (Provider<T> provider : byId.values()) {
            for (String capability : provider.descriptor.provides)
                choices.computeIfAbsent(capability, ignored -> new ArrayList<>()).add(provider);
        }
        Map<String, Provider<T>> selected = new HashMap<>();
        for (Map.Entry<String, List<Provider<T>>> entry : choices.entrySet()) {
            List<Provider<T>> candidates = entry.getValue();
            candidateChecks += candidates.size();
            candidates.sort(Comparator.<Provider<T>>comparingInt(value -> value.descriptor.priority).reversed()
                    .thenComparing(value -> value.descriptor.id));
            String override = overrides.get(entry.getKey());
            Provider<T> winner;
            if (override != null) {
                winner = byId.get(override);
                if (winner == null || !winner.descriptor.provides.contains(entry.getKey()))
                    throw new IllegalArgumentException("Invalid override: " + entry.getKey());
            } else {
                winner = candidates.get(0);
                if (candidates.size() > 1 && winner.descriptor.priority == candidates.get(1).descriptor.priority)
                    throw new IllegalArgumentException("Equal-priority conflict: " + entry.getKey());
            }
            selected.put(entry.getKey(), winner);
        }
        if (!choices.keySet().containsAll(overrides.keySet())) throw new IllegalArgumentException("Override for unknown capability");
        this.providers = Collections.unmodifiableMap(new LinkedHashMap<>(byId));
        this.selected = Collections.unmodifiableMap(selected);
    }

    private static <T> void visit(String id, Map<String, Provider<T>> providers, Set<String> visited, Set<String> visiting) {
        if (visited.contains(id)) return;
        if (!visiting.add(id)) throw new IllegalArgumentException("Dependency cycle at " + id);
        for (String dependency : providers.get(id).descriptor.dependencies) visit(dependency, providers, visited, visiting);
        visiting.remove(id); visited.add(id);
    }

    public T bind(String capability) {
        Provider<T> provider = selected.get(capability);
        if (provider == null) throw new IllegalArgumentException("Unsupported capability: " + capability);
        return provider.implementation;
    }
    public Descriptor selectedDescriptor(String capability) {
        Provider<T> provider = selected.get(capability);
        if (provider == null) throw new IllegalArgumentException("Unsupported capability: " + capability);
        return provider.descriptor;
    }
    public Collection<Descriptor> descriptors() {
        List<Descriptor> result = new ArrayList<>();
        for (Provider<T> provider : providers.values()) result.add(provider.descriptor);
        return Collections.unmodifiableList(result);
    }
    public long candidateChecks() { return candidateChecks; }

    public static final class Provider<T> {
        public final Descriptor descriptor;
        public final T implementation;
        public Provider(Descriptor descriptor, T implementation) {
            this.descriptor = Objects.requireNonNull(descriptor); this.implementation = Objects.requireNonNull(implementation);
        }
    }
    public static final class Descriptor {
        public final String id;
        public final String implementationVersion;
        public final int spiMajor;
        public final int minimumSpiMinor;
        public final int priority;
        public final Set<String> provides;
        public final Set<String> requires;
        public final Set<String> dependencies;
        public final Set<String> permissions;
        public final String threadConstraint;
        public final String lifecycleOwner;
        public final String closeResponsibility;

        public Descriptor(String id, String implementationVersion, int spiMajor, int minimumSpiMinor, int priority,
                          Set<String> provides, Set<String> requires, Set<String> dependencies, Set<String> permissions,
                          String threadConstraint, String lifecycleOwner, String closeResponsibility) {
            this.id = text(id); this.implementationVersion = text(implementationVersion);
            if (spiMajor < 0 || minimumSpiMinor < 0) throw new IllegalArgumentException("Negative SPI version");
            this.spiMajor = spiMajor; this.minimumSpiMinor = minimumSpiMinor; this.priority = priority;
            this.provides = copy(provides); this.requires = copy(requires);
            this.dependencies = copy(dependencies); this.permissions = copy(permissions);
            this.threadConstraint = text(threadConstraint); this.lifecycleOwner = text(lifecycleOwner);
            this.closeResponsibility = text(closeResponsibility);
            if (this.provides.isEmpty()) throw new IllegalArgumentException("Provider has no capability");
        }
        private static String text(String value) {
            if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("Empty descriptor field");
            return value;
        }
        private static Set<String> copy(Set<String> values) {
            Set<String> copy = new HashSet<>();
            for (String value : values) copy.add(text(value));
            return Collections.unmodifiableSet(copy);
        }
    }
}
