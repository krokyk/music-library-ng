package org.kroky.musiclib.provider;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

@ApplicationScoped
public class ProviderRegistry {

    @Inject
    Instance<DiscographyProvider> providers;

    public DiscographyProvider find(String providerId, String providerUrl) throws ProviderException {
        for (DiscographyProvider provider : providers) {
            if ((providerId == null || provider.providerId().equals(providerId)) && provider.supports(providerUrl)) {
                return provider;
            }
        }
        throw new ProviderException("No provider supports URL: " + providerUrl);
    }

}
