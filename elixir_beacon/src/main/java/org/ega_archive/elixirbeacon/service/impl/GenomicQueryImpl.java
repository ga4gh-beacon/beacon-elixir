package org.ega_archive.elixirbeacon.service.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.babelomics.csvs.lib.models.DiseaseCount;
import org.babelomics.csvs.lib.models.Variant;
import org.babelomics.csvs.lib.ws.QueryResponse;
import org.ega_archive.elixirbeacon.dto.BeaconGenomicRegionResponse;
import org.ega_archive.elixirbeacon.dto.BeaconGenomicSnpRequest;
import org.ega_archive.elixirbeacon.dto.BeaconGenomicSnpResponse;
import org.ega_archive.elixirbeacon.dto.Handover;
import org.ega_archive.elixirbeacon.dto.HandoverType;
import org.ega_archive.elixirbeacon.service.GenomicQuery;
import org.ega_archive.elixirbeacon.utils.ParseResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class GenomicQueryImpl implements GenomicQuery {

  @Autowired
  private ParseResponse parseResponse;

  @Override
  public BeaconGenomicSnpResponse queryBeaconGenomicSnp(List<String> datasetStableIds,
      String alternateBases, String referenceBases, String chromosome, Integer start,
      String referenceGenome, String includeDatasetResponses, List<String> filters) {

    // TODO: check referenceGenome is GRCh37 -> do it in checkParams
    // TODO: Add new endpoint to CSVS to return variants by dataset and use it here
    // TODO: Move all URLs to the properties file

    Map<String, Object> info = findRegionVariants(chromosome, start, start + 1,
        referenceBases, alternateBases, datasetStableIds, filters);

    BeaconGenomicSnpResponse beaconGenomicSnpResponse = new BeaconGenomicSnpResponse();
    beaconGenomicSnpResponse.setExists(findVariantCount(info));
    callToCellBase(beaconGenomicSnpResponse, Arrays.asList(chromosome + ":" + start + ":" + referenceBases + ":" + alternateBases));
    // TODO: fill the request
    BeaconGenomicSnpRequest request = new BeaconGenomicSnpRequest();
    beaconGenomicSnpResponse.setRequest(request);
    beaconGenomicSnpResponse.setInfo(info);
    return beaconGenomicSnpResponse;
  }

  @Override
  public BeaconGenomicRegionResponse queryBeaconGenomicRegion(List<String> datasetStableIds,
      String referenceBases, String chromosome, Integer start, Integer end, String referenceGenome,
      String includeDatasetResponses, List<String> filters) {

    Map<String, Object> info = findRegionVariants(chromosome, start, end, referenceBases, null,
        datasetStableIds, filters);

    BeaconGenomicRegionResponse response = new BeaconGenomicRegionResponse();
    // TODO: fill the request
    response.setExists(findVariantCount(info));
    response.setInfo(info);
    return response;
  }

  /**
   * This function does a call to Cell Base with the variants to get the rs IDs and fills the Handover.
   *
   * @param beaconGenomicSnpResponse
   * @param variants
   */
  private void callToCellBase(BeaconGenomicSnpResponse beaconGenomicSnpResponse, List<String> variants) {
    // Call to cellBase and get the rs ID
    String urlCellBase = "http://cellbase.clinbioinfosspa.es/cb/webservices/rest/v4/hsapiens/genomic/variant/";
    urlCellBase += variants.stream().collect(Collectors.joining(","));
    urlCellBase += "/annotation";
    String cellBaseResponse = parseResponse.parseResponse(urlCellBase, null);

    List<String> rsIds = new ArrayList<>();

    JsonParser parser = new JsonParser();
    JsonElement jsonTree = parser.parse(cellBaseResponse);
    JsonObject cellBaseObj = jsonTree.getAsJsonObject();
    JsonArray variantArray = cellBaseObj.getAsJsonArray("response");
    for (JsonElement elem : variantArray) {
      JsonObject object = elem.getAsJsonObject();
      JsonArray variantResults = object.getAsJsonArray("result");
      for(JsonElement variantElem : variantResults) {
        JsonObject variantObj = variantElem.getAsJsonObject();
        JsonElement rsIdElem = variantObj.get("id");
        if(rsIdElem != null) {
          rsIds.add(rsIdElem.getAsString());
          log.debug("rs ID: {}", rsIdElem.getAsString());
        }
      }
    }

    List<Handover> handoverList = new ArrayList<>();
    for (String rsId : rsIds) {
      handoverList.add(Handover.builder()
          .handoverType(HandoverType.builder()
              .id("data_1106")
              .label("dbSNP ID")
              .build())
          .url("https://www.ncbi.nlm.nih.gov/snp/?term=" + rsId)
          .note("Link to dbSNP database")
          .build());

      handoverList.add(Handover.builder()
          .handoverType(HandoverType.builder()
              .id("data_1106")
              .label("dbSNP ID")
              .build())
          .url("https://api.ncbi.nlm.nih.gov/variation/v0/beta/refsnp/" + rsId.replaceFirst("rs", ""))
          .note("Link to dbSNP API")
          .build());
    }
    beaconGenomicSnpResponse.setBeaconHandover(handoverList);
  }

  private boolean findVariantCount(Map<String, Object> info) {
    Integer variantCount = 0;
    String value = (String) info.get("variantCount");
    if (StringUtils.isNotBlank(value)) {
      try {
        variantCount = Integer.parseInt(value);
      } catch (NumberFormatException ex) {
        // Ignore exception
      }
    }
    return variantCount > 0;
  }

  private Map<String, Object> findRegionVariants(String chromosome, Integer start, Integer end,
      String referenceBases, String alternateBases, List<String> datasetStableIds,
      List<String> filters) {

    String technologyFilter = null;
    if (null != filters) {
      // TODO: load this data to the ontology table and check it
      String technologyFilterValues = filters.stream()
          .filter(filter -> filter.startsWith("myDictionary"))
          .map(filter -> filter.split(":", 2)[1])
          .collect(Collectors.joining(","));
      technologyFilter =
          StringUtils.isNotBlank(technologyFilterValues) ? "&technologies=" + technologyFilterValues
              : null;
    }

    String url = "http://csvs.clinbioinfosspa.es:8080/csvs/rest/variants/fetch?regions=";
    url = url + chromosome + ":" + start + "-" + end;

    if (datasetStableIds != null && !datasetStableIds.isEmpty()) {
      url += "&diseases=" + String.join(",", datasetStableIds);
    }
    if (StringUtils.isNotBlank(technologyFilter)) {
      url += technologyFilter;
    }

    log.debug("url {}", url);
    QueryResponse<Variant> variantQueryResponse = parseResponse
        .parseCsvsResponse(url, Variant.class);
    log.debug("response: {}", variantQueryResponse);

    boolean isRegionQuery = StringUtils.isBlank(alternateBases) && end != null;

    boolean exists =
        variantQueryResponse.getNumTotalResults() > 0 && variantQueryResponse.getResult() != null
            && variantQueryResponse.getResult().get(0) != null;
    Map<String, Object> info = new HashMap<>();
    int numVariants = 0;
    if (exists) {
      List<Variant> result = variantQueryResponse.getResult();
      for (Variant variant : result) {
        if (checkParameters(referenceBases, variant.getReference(), alternateBases,
            variant.getAlternate(), isRegionQuery)) {
          numVariants++;

          DiseaseCount stats = variant.getStats();
          info.put("0/0", String.valueOf(stats.getGt00()));
          info.put("0/1", String.valueOf(stats.getGt01()));
          info.put("1/1", String.valueOf(stats.getGt11()));
          info.put("./.", String.valueOf(stats.getGtmissing()));
          info.put("0 Freq", String.valueOf(stats.getRefFreq()));
          info.put("1 Freq", String.valueOf(stats.getAltFreq()));
          info.put("MAF", String.valueOf(stats.getMaf()));
          // TODO: consider if we should show this info
//          info.add(new KeyValuePair("Num sample regions", String.valueOf(stats.getSumSampleRegions())));
//          info.add(new KeyValuePair("Total GTs", String.valueOf(stats.getTotalGts())));
        }
      }
    }
    if (numVariants > 1) {
      info = new HashMap<>();
    }
    if (numVariants > 0) {
      info.put("variantCount", String.valueOf(numVariants));
    }
    return info;
  }

  private boolean checkParameters(String referenceBases, String reference, String alternateBases,
      String alternate, boolean isRegionQuery) {
    if (isRegionQuery) {
      return StringUtils.isBlank(referenceBases) || StringUtils
          .equalsIgnoreCase(referenceBases, reference);
    } else {
      return StringUtils.equalsIgnoreCase(referenceBases, reference)
          && (StringUtils.equalsIgnoreCase(alternateBases, "N")
          || StringUtils.equalsIgnoreCase(alternateBases, alternate));
    }
  }

}