package com.home.infrastructure.external.complex;

import com.home.application.ingest.metadata.ComplexMetadataEnrichmentClient;
import com.home.application.ingest.metadata.ComplexMetadataLookup;
import com.home.application.ingest.metadata.ComplexMetadataResolution;
import com.home.application.ingest.metadata.ComplexMetadataResolutionPolicy;
import com.home.application.ingest.metadata.OdcComplexMetadataResolver;
import com.home.application.ingest.metadata.OdcloudPnuPrefixAliasLookup;
import java.util.Objects;
import org.springframework.web.client.RestClient;

public class PublicComplexMetadataResolver implements ComplexMetadataEnrichmentClient, OdcComplexMetadataResolver {

    private final OdcComplexMetadataAdapter odcloud;
    private final BuildingComplexMetadataAdapter building;
    private final boolean buildingFallbackEnabled;
    private final ComplexMetadataResolutionPolicy resolutionPolicy;

    public PublicComplexMetadataResolver(
            RestClient odcloudRestClient,
            String odcloudServiceKey,
            String odcloudAptPath,
            RestClient bldRestClient,
            String bldServiceKey,
            String bldRecapPath,
            String recapPath) {
        this(
                odcloudRestClient,
                null,
                odcloudServiceKey,
                odcloudAptPath,
                bldRestClient,
                null,
                bldServiceKey,
                bldRecapPath,
                recapPath,
                true,
                new ComplexMetadataResolutionPolicy(),
                OdcloudPnuPrefixAliasLookup.empty());
    }

    public PublicComplexMetadataResolver(
            RestClient odcloudRestClient,
            String odcloudBaseUrl,
            String odcloudServiceKey,
            String odcloudAptPath,
            RestClient bldRestClient,
            String bldBaseUrl,
            String bldServiceKey,
            String bldRecapPath,
            String recapPath,
            boolean buildingFallbackEnabled) {
        this(
                odcloudRestClient,
                odcloudBaseUrl,
                odcloudServiceKey,
                odcloudAptPath,
                bldRestClient,
                bldBaseUrl,
                bldServiceKey,
                bldRecapPath,
                recapPath,
                buildingFallbackEnabled,
                new ComplexMetadataResolutionPolicy(),
                OdcloudPnuPrefixAliasLookup.empty());
    }

    public PublicComplexMetadataResolver(
            RestClient odcloudRestClient,
            String odcloudBaseUrl,
            String odcloudServiceKey,
            String odcloudAptPath,
            RestClient bldRestClient,
            String bldBaseUrl,
            String bldServiceKey,
            String bldRecapPath,
            String recapPath,
            boolean buildingFallbackEnabled,
            OdcloudPnuPrefixAliasLookup aliasLookup) {
        this(
                odcloudRestClient,
                odcloudBaseUrl,
                odcloudServiceKey,
                odcloudAptPath,
                bldRestClient,
                bldBaseUrl,
                bldServiceKey,
                bldRecapPath,
                recapPath,
                buildingFallbackEnabled,
                new ComplexMetadataResolutionPolicy(),
                aliasLookup);
    }

    PublicComplexMetadataResolver(
            RestClient odcloudRestClient,
            String odcloudBaseUrl,
            String odcloudServiceKey,
            String odcloudAptPath,
            RestClient bldRestClient,
            String bldBaseUrl,
            String bldServiceKey,
            String bldRecapPath,
            String recapPath,
            boolean buildingFallbackEnabled,
            ComplexMetadataResolutionPolicy resolutionPolicy,
            OdcloudPnuPrefixAliasLookup aliasLookup) {
        this.odcloud = new OdcComplexMetadataAdapter(
                odcloudRestClient, odcloudBaseUrl, odcloudServiceKey, odcloudAptPath, aliasLookup);
        this.building =
                new BuildingComplexMetadataAdapter(bldRestClient, bldBaseUrl, bldServiceKey, bldRecapPath, recapPath);
        this.buildingFallbackEnabled = buildingFallbackEnabled;
        this.resolutionPolicy = Objects.requireNonNull(resolutionPolicy);
    }

    @Override
    public boolean isConfigured() {
        return odcloud.isConfigured() || (buildingFallbackEnabled && building.isConfigured());
    }

    @Override
    public ComplexMetadataResolution resolve(ComplexMetadataLookup lookup) {
        ComplexMetadataResolution odcResolution = odcloud.resolve(lookup);
        return resolutionPolicy.resolve(
                lookup.pnu(), buildingFallbackEnabled, odcResolution, () -> building.resolve(lookup.pnu()));
    }

    @Override
    public ComplexMetadataResolution resolveOdc(ComplexMetadataLookup lookup) {
        return odcloud.resolve(lookup);
    }

    @Override
    public boolean isOdcConfigured() {
        return odcloud.isConfigured();
    }
}
