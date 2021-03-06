package org.ega_archive.elixirbeacon.service;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javassist.NotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.NotImplementedException;
import org.apache.commons.lang3.StringUtils;
import org.ega_archive.elixirbeacon.constant.BeaconConstants;
import org.ega_archive.elixirbeacon.convert.Operations;
import org.ega_archive.elixirbeacon.dto.Beacon;
import org.ega_archive.elixirbeacon.dto.BeaconAlleleRequest;
import org.ega_archive.elixirbeacon.dto.BeaconAlleleResponse;
import org.ega_archive.elixirbeacon.dto.BeaconRequest;
import org.ega_archive.elixirbeacon.dto.Dataset;
import org.ega_archive.elixirbeacon.dto.DatasetAlleleResponse;
import org.ega_archive.elixirbeacon.dto.Error;
import org.ega_archive.elixirbeacon.dto.Handover;
import org.ega_archive.elixirbeacon.dto.HandoverType;
import org.ega_archive.elixirbeacon.enums.ErrorCode;
import org.ega_archive.elixirbeacon.enums.FilterDatasetResponse;
import org.ega_archive.elixirbeacon.enums.VariantType;
import org.ega_archive.elixirbeacon.model.elixirbeacon.BeaconDataSummary;
import org.ega_archive.elixirbeacon.model.elixirbeacon.BeaconDataset;
import org.ega_archive.elixirbeacon.model.elixirbeacon.BeaconDatasetConsentCode;
import org.ega_archive.elixirbeacon.properties.HandoverConfig;
import org.ega_archive.elixirbeacon.properties.SampleRequests;
import org.ega_archive.elixirbeacon.repository.elixirbeacon.BeaconDatasetConsentCodeRepository;
import org.ega_archive.elixirbeacon.repository.elixirbeacon.BeaconDatasetRepository;
import org.ega_archive.elixirbeacon.repository.elixirbeacon.BeaconSummaryDataRepository;
import org.ega_archive.elixircore.enums.DatasetAccessType;
import org.ega_archive.elixircore.helper.CommonQuery;
import org.ega_archive.elixircore.util.StoredProcedureUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.domain.Sort.Order;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ElixirBeaconServiceImpl implements ElixirBeaconService {

  @Autowired
  private SampleRequests sampleRequests;

  @Autowired
  private HandoverConfig handoverConfig;

  @Autowired
  private BeaconDatasetRepository beaconDatasetRepository;
  
  @Autowired
  private BeaconSummaryDataRepository beaconDataRepository;
  
  @Autowired
  private BeaconDatasetConsentCodeRepository beaconDatasetConsentCodeRepository;

  @Autowired
  private AuthService authService;

  @Override
  public Beacon listDatasets(CommonQuery commonQuery, String referenceGenome)
      throws NotFoundException {

    List<String> authorizedDatasets = new ArrayList<>();

    boolean isAuthenticated = false;
    String authorizationHeader = authService.getAuthorizationHeader();
    if (StringUtils.isNotBlank(authorizationHeader)) {
      isAuthenticated = true;
      authorizedDatasets = authService.findAuthorizedDatasets(authorizationHeader);
    }

    commonQuery.setSort(new Sort(new Order(Direction.ASC, "id")));

    List<Dataset> convertedDatasets = new ArrayList<>();
    Page<BeaconDataset> allDatasets = null;

    if (StringUtils.isNotBlank(referenceGenome)) {
      referenceGenome = StringUtils.lowerCase(referenceGenome);
      allDatasets =
          beaconDatasetRepository.findByReferenceGenome(referenceGenome, commonQuery.getPageable());
    } else {
      allDatasets = beaconDatasetRepository.findAll(commonQuery);
    }

    BigInteger size = BigInteger.valueOf(0L);
    for (BeaconDataset dataset : allDatasets) {
      DatasetAccessType accessType = DatasetAccessType.parse(dataset.getAccessType());
      boolean authorized = false;
      if (accessType == DatasetAccessType.PUBLIC
          || (isAuthenticated && accessType == DatasetAccessType.REGISTERED)
          || authorizedDatasets.contains(dataset.getStableId())) {
        authorized = true;
      }
      List<BeaconDatasetConsentCode> ccDataUseConditions =
          beaconDatasetConsentCodeRepository.findByDatasetId(dataset.getId());

      convertedDatasets.add(Operations.convert(dataset, authorized, ccDataUseConditions));

      size = dataset.getVariantCnt().add(size);
    }

    Map<String, String> info = new HashMap<>();
    info.put(BeaconConstants.SIZE, size.toString());

    Beacon response = new Beacon();
    response.setDatasets(convertedDatasets);
    response.setInfo(info);
    response.setSampleAlleleRequests(getSampleAlleleRequests());
    return response;
  }

  private List<BeaconAlleleRequest> getSampleAlleleRequests() {
    List<BeaconAlleleRequest> sampleAlleleRequests = new ArrayList<BeaconAlleleRequest>();
    sampleAlleleRequests.add(BeaconAlleleRequest.builder()
        .assemblyId(sampleRequests.getAssemblyId1())
        .start(sampleRequests.getStart1())
        .startMin(sampleRequests.getStartMin1())
        .startMax(sampleRequests.getStartMax1())
        .end(sampleRequests.getEnd1())
        .endMin(sampleRequests.getEndMin1())
        .endMax(sampleRequests.getEndMax1())
        .referenceName(sampleRequests.getReferenceName1())
        .referenceBases(sampleRequests.getReferenceBases1())
        .alternateBases(StringUtils.isBlank(sampleRequests.getAlternateBases1()) ? null : sampleRequests.getAlternateBases1())
        .datasetIds(sampleRequests.getDatasetIds1().isEmpty() ? null : sampleRequests.getDatasetIds1())
        .build());
    sampleAlleleRequests.add(BeaconAlleleRequest.builder()
        .assemblyId(sampleRequests.getAssemblyId2())
        .start(sampleRequests.getStart2())
        .startMin(sampleRequests.getStartMin2())
        .startMax(sampleRequests.getStartMax2())
        .end(sampleRequests.getEnd2())
        .endMin(sampleRequests.getEndMin2())
        .endMax(sampleRequests.getEndMax2())
        .referenceName(sampleRequests.getReferenceName2())
        .referenceBases(sampleRequests.getReferenceBases2())
        .alternateBases(StringUtils.isBlank(sampleRequests.getAlternateBases2()) ? null : sampleRequests.getAlternateBases2())
        .datasetIds(sampleRequests.getDatasetIds2().isEmpty() ? null : sampleRequests.getDatasetIds2())
        .build());
    sampleAlleleRequests.add(BeaconAlleleRequest.builder()
        .assemblyId(sampleRequests.getAssemblyId3())
        .start(sampleRequests.getStart3())
        .startMin(sampleRequests.getStartMin3())
        .startMax(sampleRequests.getStartMax3())
        .end(sampleRequests.getEnd3())
        .endMin(sampleRequests.getEndMin3())
        .endMax(sampleRequests.getEndMax3())
        .referenceBases(sampleRequests.getReferenceBases3())
        .referenceName(sampleRequests.getReferenceName3())
        .alternateBases(StringUtils.isBlank(sampleRequests.getAlternateBases3()) ? null : sampleRequests.getAlternateBases3())
        .datasetIds(sampleRequests.getDatasetIds3().isEmpty() ? null : sampleRequests.getDatasetIds3())
        .build());
    return sampleAlleleRequests;
  }

  @Override
  public BeaconAlleleResponse queryBeacon(List<String> datasetStableIds, String variantType,
      String alternateBases, String referenceBases, String chromosome, Integer start,
      Integer startMin, Integer startMax, Integer end, Integer endMin, Integer endMax,
      String mateName, String referenceGenome, String includeDatasetResponses) {

    BeaconAlleleResponse result = new BeaconAlleleResponse();
    
    alternateBases = StringUtils.upperCase(alternateBases);
    referenceBases = StringUtils.upperCase(referenceBases);
    
    BeaconAlleleRequest request = BeaconAlleleRequest.builder()
        .alternateBases(alternateBases)
        .referenceBases(referenceBases)
        .referenceName(chromosome)
        .datasetIds(datasetStableIds)
        .start(start)
        .startMin(startMin)
        .startMax(startMax)
        .end(end)
        .endMin(endMin)
        .endMax(endMax)
        .mateName(mateName)
        .variantType(variantType)
        .assemblyId(referenceGenome)
        .includeDatasetResponses(FilterDatasetResponse.parse(includeDatasetResponses))
        .build();
    result.setAlleleRequest(request);
    
    VariantType type = VariantType.parse(variantType);

    List<Integer> datasetIds =
        checkParams(result, datasetStableIds, type, alternateBases, referenceBases, chromosome,
            start, startMin, startMax, end, endMin, endMax, mateName, referenceGenome);

    boolean globalExists = false;
    if (result.getError() == null) {
      globalExists = queryDatabase(datasetIds, type, referenceBases, alternateBases, chromosome,
          start, startMin, startMax, end, endMin, endMax, referenceGenome, result);
    }
    result.setExists(globalExists);
    return result;
  }

  @Override
  public List<Integer> checkParams(BeaconAlleleResponse result, List<String> datasetStableIds,
      VariantType type, String alternateBases, String referenceBases, String chromosome,
      Integer start, Integer startMin, Integer startMax, Integer end, Integer endMin,
      Integer endMax, String mateName, String referenceGenome) {

    List<Integer> datasetIds = new ArrayList<>();

    if (StringUtils.isBlank(chromosome) || StringUtils.isBlank(referenceGenome) || StringUtils.isBlank(referenceBases)) {
      Error error = Error.builder()
          .errorCode(ErrorCode.GENERIC_ERROR)
          .message("All 'referenceName', 'referenceBases' and/or 'assemblyId' are required")
          .build();
      result.setError(error);
      return datasetIds;
    }
    if (StringUtils.isNotBlank(referenceGenome)){
      boolean matches = Pattern.matches("^grch[1-9]{2}$", StringUtils.lowerCase(referenceGenome));
      if (!matches) {
        Error error = Error.builder().errorCode(ErrorCode.GENERIC_ERROR)
            .message("Invalid 'assemblyId' parameter, GRC notation required (e.g. GRCh37)")
            .build();
        result.setError(error);
        return datasetIds;
      }
    }
    if (StringUtils.isNotBlank(chromosome)){
      boolean matches = Pattern.matches("^([1-9][0-9]|[1-9]|X|Y|MT)$", chromosome);
      if (!matches) {
        Error error = Error.builder().errorCode(ErrorCode.GENERIC_ERROR)
            .message("Invalid 'referenceName' parameter, accepted values are 1-22, X, Y, MT")
            .build();
        result.setError(error);
        return datasetIds;
      }
    }
    
    if (type == null && StringUtils.isBlank(alternateBases)) {
      Error error = Error.builder()
          .errorCode(ErrorCode.GENERIC_ERROR)
          .message("Either 'alternateBases' or 'variantType' is required")
          .build();
      result.setError(error);
    } else if (type != null && StringUtils.isNotBlank(alternateBases)
        && !StringUtils.equalsIgnoreCase(alternateBases, "N")) {
      Error error = Error.builder().errorCode(ErrorCode.GENERIC_ERROR)
          .message(
              "If 'variantType' is provided then 'alternateBases' must be empty or equal to 'N'")
          .build();
      result.setError(error);
      return datasetIds;
    }
    
    if (start == null) {
      if(end != null) {
        Error error = Error.builder()
            .errorCode(ErrorCode.GENERIC_ERROR)
            .message("'start' is required if 'end' is provided")
            .build();
        result.setError(error);
        return datasetIds;
      } else if (startMin == null && startMax == null && endMin == null && endMax == null) {
        Error error = Error.builder()
            .errorCode(ErrorCode.GENERIC_ERROR)
            .message("Either 'start' or all of 'startMin', 'startMax', 'endMin' and 'endMax' are required")
            .build();
        result.setError(error);
        return datasetIds;
      } else if (startMin == null || startMax == null || endMin == null || endMax == null) {
        Error error = Error.builder()
            .errorCode(ErrorCode.GENERIC_ERROR)
            .message("All of 'startMin', 'startMax', 'endMin' and 'endMax' are required")
            .build();
        result.setError(error);
        return datasetIds;
      }
    } else if (startMin != null || startMax != null || endMin != null || endMax != null) {
      Error error = Error.builder()
          .errorCode(ErrorCode.GENERIC_ERROR)
          .message("'start' cannot be provided at the same time as 'startMin', 'startMax', 'endMin' and 'endMax'")
          .build();
      result.setError(error);
      return datasetIds;
    } else if (end == null && StringUtils.equalsIgnoreCase(referenceBases, "N")) {
      Error error = Error.builder()
          .errorCode(ErrorCode.GENERIC_ERROR)
          .message("'referenceBases' cannot be 'N' if 'start' is provided and 'end' is missing")
          .build();
      result.setError(error);
      return datasetIds;
    }

    datasetIds = authService.checkDatasets(datasetStableIds, referenceGenome);

    // Allele has a valid value
    if (StringUtils.isNotBlank(alternateBases)) {
      boolean matches = Pattern.matches("^([ACGTN]+)$", alternateBases);
      if (!matches) {
        Error error = Error.builder().errorCode(ErrorCode.GENERIC_ERROR)
            .message("Invalid 'alternateBases' parameter, it must match the pattern ^([ACGTN]+)$")
            .build();
        result.setError(error);
        return datasetIds;
      }
    }
    if (StringUtils.isNotBlank(referenceBases)) {
      boolean matches = Pattern.matches("^([ACGTN]+)$", referenceBases);
      if (!matches) {
        Error error = Error.builder().errorCode(ErrorCode.GENERIC_ERROR)
            .message("Invalid 'referenceBases' parameter, it must match the pattern ^([ACGTN]+)$").build();
        result.setError(error);
        return datasetIds;
      }
    }
    if (StringUtils.isNotBlank(mateName)) {
      throw new NotImplementedException("Queries using 'mateName' are not implemented");
    }

//    if (type != null && type != VariantType.SNP && type != VariantType.INSERTION
//        && type != VariantType.DELELETION && type != VariantType.DUPLICATION) {
//      Error error = Error.builder().errorCode(ErrorCode.GENERIC_ERROR)
//          .message("Invalid 'variantType' parameter").build();
//      result.setError(error);
//      return datasetIds;
//    }

//    if (type != VariantType.SNP && type != VariantType.INSERTION && type != VariantType.DELELETION
//        && type != VariantType.DUPLICATION) {
//      Error error = Error.builder()
//          .errorCode(ErrorCode.GENERIC_ERROR)
//          .message("Invalid alternateBases parameter")
//          .build();
//      result.setError(error);
//      return datasetIds;
//    }

    return datasetIds;
  }

  private boolean queryDatabase(List<Integer> datasetIds, VariantType type, String referenceBases,
      String alternateBases, String chromosome, Integer start, Integer startMin, Integer startMax,
      Integer end, Integer endMin, Integer endMax, String referenceGenome, BeaconAlleleResponse result) {

    long numResults = 0L;
    boolean globalExists = false;
    String variantType = type != null ? type.getType() : null;
    log.debug(
        "Calling query with params: variantType={}, start={}, startMin={}, startMax={}, end={}, "
            + "endMin={}, endMax={}, chrom={}, reference={}, alternate={}, assemlbyId={}, "
            + "datasetIds={}", variantType, start, startMin, startMax, end, endMin, endMax,
        chromosome, referenceBases, alternateBases, referenceGenome, datasetIds);

    List<BeaconDataSummary> dataList = beaconDataRepository
        .searchForVariantsQuery(variantType, start,
            startMin, startMax, end, endMin, endMax, chromosome, referenceBases, alternateBases,
            referenceGenome, StoredProcedureUtils.joinArrayOfInteger(datasetIds));
    numResults = dataList.size();
    globalExists = numResults > 0;

    for (BeaconDataSummary data : dataList) {
      if (result.getAlleleRequest().getIncludeDatasetResponses() == FilterDatasetResponse.ALL
          || result.getAlleleRequest().getIncludeDatasetResponses() == FilterDatasetResponse.HIT) {
        DatasetAlleleResponse datasetResponse = new DatasetAlleleResponse();
        BeaconDataset dataset = beaconDatasetRepository.findOne(data.getDatasetId());
        datasetResponse.setDatasetId(dataset.getStableId());
        datasetResponse.setExists(true);
        datasetResponse.setFrequency(data.getFrequency());
        datasetResponse.setVariantCount(data.getVariantCnt());
        datasetResponse.setCallCount(data.getCallCnt());
        datasetResponse.setSampleCount(data.getSampleCnt());
        datasetResponse.setDatasetHandover(addDatasetHandovers(dataset.getStableId()));
        result.addDatasetAlleleResponse(datasetResponse);
      }
    }

    Set<Integer> datasetIdsWithData =
        dataList.stream().map(data -> data.getDatasetId()).collect(Collectors.toSet());

    // Check that all requested datasets are present in the response
    // (maybe some of them are not present because they have no data for this query)
    @SuppressWarnings("unchecked")
    Collection<Integer> missingDatasets =
        CollectionUtils.disjunction(datasetIds, datasetIdsWithData);

    if (!missingDatasets.isEmpty() && (result.getAlleleRequest()
        .getIncludeDatasetResponses() == FilterDatasetResponse.MISS
        || result.getAlleleRequest().getIncludeDatasetResponses() == FilterDatasetResponse.ALL)) {
      for (Integer datasetId : missingDatasets) {
        DatasetAlleleResponse datasetResponse = new DatasetAlleleResponse();
        BeaconDataset dataset = beaconDatasetRepository.findOne(datasetId);
        datasetResponse.setDatasetId(dataset.getStableId());
        datasetResponse.setExists(false);
        datasetResponse.setDatasetHandover(addDatasetHandovers(dataset.getStableId()));
        result.addDatasetAlleleResponse(datasetResponse);
      }
    }
    return globalExists;
  }

  private List<Handover> addDatasetHandovers(String stableId) {
    List<Handover> datasetHandovers = new ArrayList<>();

    handoverConfig.getDatasetHandover().stream()
        .filter(p -> StringUtils.equalsIgnoreCase(p.getStableId(), stableId))
        .forEach(prop -> {
          Handover handover = new Handover();
          HandoverType handoverType = new HandoverType();
          handoverType.setId(prop.getId());
          handoverType.setLabel(prop.getLabel());
          handover.setHandoverType(handoverType);
          handover.setUrl(prop.getUrl());
          handover.setNote(prop.getNote());
          datasetHandovers.add(handover);
        });
    return datasetHandovers;
  }

  @Override
  public BeaconAlleleResponse queryBeacon(BeaconRequest request) {

    return queryBeacon(request.getDatasetIds(), request.getVariantType(),
        request.getAlternateBases(), request.getReferenceBases(), request.getReferenceName(),
        request.getStart(), request.getStartMin(), request.getStartMax(), request.getEnd(),
        request.getEndMin(), request.getEndMax(), request.getMateName(), request.getAssemblyId(),
        request.getIncludeDatasetResponses());
  }

}
