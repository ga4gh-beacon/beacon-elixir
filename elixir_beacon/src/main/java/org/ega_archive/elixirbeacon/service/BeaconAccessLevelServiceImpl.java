package org.ega_archive.elixirbeacon.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.ega_archive.elixirbeacon.constant.BeaconConstants;
import org.ega_archive.elixirbeacon.constant.CsvsConstants;
import org.ega_archive.elixirbeacon.dto.AccessLevelResponse;
import org.ega_archive.elixirbeacon.dto.Error;
import org.ega_archive.elixirbeacon.enums.ErrorCode;
import org.ega_archive.elixirbeacon.model.elixirbeacon.DatasetAccessLevel;
import org.ega_archive.elixircore.exception.NotImplementedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class BeaconAccessLevelServiceImpl implements BeaconAccessLevelService {

  @Value("${access.levels.default.yaml.filename}")
  private String defaultAccessLevelFileName;

  public static final String ACCESS_LEVEL_SUMMARY = "accessLevelSummary";

  @Override
  public AccessLevelResponse listAccessLevels(List<String> fields,
      List<String> datasetStableIds, String level, boolean includeFieldDetails,
      boolean includeDatasetDetails, String fileName) {

    // TODO implement search by "level"
    if (StringUtils.isNotBlank(level)) {
      throw new NotImplementedException("Searching by 'level' is not implemented yet!");
    }

    AccessLevelResponse response = new AccessLevelResponse();
    response.setBeaconId(BeaconConstants.BEACON_ID);

    ObjectMapper objectMapper = new ObjectMapper(new YAMLFactory());
    try {
      InputStream input = this.getClass().getClassLoader().getResourceAsStream(fileName);
      @SuppressWarnings("unchecked")
      Map<String, Object> map = objectMapper.readValue(input, Map.class);
      map = searchFieldsInMap(map, fields, includeFieldDetails);
      if (map == null || map.isEmpty()) {
        Error error = new Error(ErrorCode.NOT_FOUND, "Field not found");
        response.setError(error);
      }
      response.setFields(map);
    } catch (IOException e) {
      log.error("Exception parsing yaml", e);
    }

    Iterable<DatasetAccessLevel> datasetAccessLevels = findDatasets(fields, datasetStableIds,
        includeFieldDetails, includeDatasetDetails, response);

    if (datasetStableIds != null && !datasetStableIds.isEmpty()
        && (datasetAccessLevels == null || !datasetAccessLevels.iterator().hasNext())) {
      Error error = new Error(ErrorCode.NOT_FOUND, "Dataset(s) not found");
      response.setError(error);
    }
    if (response.getError() == null) {
      Map<String, Object> datasetsMap = fillDatasetsMap(datasetAccessLevels, includeFieldDetails,
          includeDatasetDetails);
      response.setDatasets(datasetsMap);
    }

    return response;
  }

  @Override
  public AccessLevelResponse listAccessLevels(List<String> fields, List<String> datasetStableIds,
      String level, boolean includeFieldDetails, boolean includeDatasetDetails) {

    return listAccessLevels(fields, datasetStableIds, level, includeFieldDetails,
        includeDatasetDetails, defaultAccessLevelFileName);
  }

  private Iterable<DatasetAccessLevel> findDatasets(List<String> fields, List<String> datasetStableIds,
      boolean includeFieldDetails, boolean includeDatasetDetails, AccessLevelResponse response) {

    List<DatasetAccessLevel>  datasetAccessLevels = CsvsConstants.CSVS_DATASET_ACCESS_LEVEL;

    if (response.getError() == null && datasetStableIds != null && !datasetStableIds.isEmpty()) {
      datasetAccessLevels =  datasetAccessLevels.stream().filter(acc -> datasetStableIds.contains(acc.getId().getDatasetStableId())).collect(Collectors.toList());
    }
    if (fields != null && !fields.isEmpty()) {
        if (includeFieldDetails) {
          // Look for a match among the parent fields or children
          datasetAccessLevels= datasetAccessLevels.stream().filter(acc -> fields.contains(acc.getId().getParentField().toLowerCase()) || fields.contains(acc.getId().getField().toLowerCase())).collect(Collectors.toList());
        } else {
          // Look for a match only among the parent fields
          datasetAccessLevels = datasetAccessLevels.stream().filter(acc -> fields.contains(acc.getId().getField().toLowerCase())).collect(Collectors.toList());
        }
    } else {
        if(!includeDatasetDetails) {
          // Only show the summary
          datasetAccessLevels = datasetAccessLevels.stream().filter(acc -> ACCESS_LEVEL_SUMMARY.toLowerCase().equals(acc.getId().getParentField().toLowerCase())).collect(Collectors.toList());
        }
    }

    return datasetAccessLevels;
  }

  private Map<String, Object> fillDatasetsMap(Iterable<DatasetAccessLevel> datasetFields,
      boolean includeFieldDetails, boolean includeDatasetDetails) {

    Map<String, Object> datasetsMap = new LinkedHashMap<>();
    datasetFields.forEach(d -> {
      if (!includeDatasetDetails) {
        if (ACCESS_LEVEL_SUMMARY.equalsIgnoreCase(d.getId().getParentField())) {
          datasetsMap.put(d.getId().getDatasetStableId(), d.getAccessLevel());
        }
      } else { // includeDatasetDetails = true
        if (ACCESS_LEVEL_SUMMARY.equalsIgnoreCase(d.getId().getParentField())) {
          // Skip field
          return;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> parentFieldsMap = (Map<String, Object>) datasetsMap.get(d.getId().getDatasetStableId());
        if (parentFieldsMap == null) {
          parentFieldsMap = new LinkedHashMap<>();
        }

        if (includeFieldDetails) {
          @SuppressWarnings("unchecked")
          Map<String, String> fieldsMap = (Map<String, String>) parentFieldsMap.get(d.getId().getParentField());
          if (fieldsMap == null) {
            fieldsMap = new LinkedHashMap<>();
          }
          fieldsMap.put(d.getId().getField(), d.getAccessLevel());
          parentFieldsMap.put(d.getId().getParentField(), fieldsMap);
        } else {
          parentFieldsMap.put(d.getId().getParentField(), d.getAccessLevel());
        }
        datasetsMap.put(d.getId().getDatasetStableId(), parentFieldsMap);
      }
    });
    return datasetsMap;
  }

  private Map<String, Object> searchFieldsInMap(Map<String, Object> map, List<String> fields,
      boolean includeFieldDetails) {

    Map<String, Object> newMap;

    if (fields != null && !fields.isEmpty()) {
      newMap = new LinkedHashMap<>();
      fields.replaceAll(String::toLowerCase);

      map.entrySet()
          .stream()
          .filter(entry -> fields.contains(entry.getKey().toLowerCase())
              || (includeFieldDetails && CollectionUtils
              .containsAny(convertToLowerCase(entry), fields))
          )
          .forEach(entry -> {
            String key = entry.getKey();
            if (!fields.contains(entry.getKey().toLowerCase()) &&
                (includeFieldDetails && CollectionUtils.containsAny(convertToLowerCase(entry), fields))) {
              // Match found among the inner keys
              copyInnerMap(fields, newMap, entry);
            } else if(!includeFieldDetails) {
              // Match found among the outer keys -> Show summary value
              @SuppressWarnings("unchecked")
              Map<String, String> value = (Map<String, String>) entry.getValue();
              newMap.put(key, value.get(ACCESS_LEVEL_SUMMARY));
            } else {
              // Match found among the outer keys
              newMap.put(key, entry.getValue());
            }
          });
    } else if(!includeFieldDetails) {
      newMap = showSummary(map);
    } else {
      newMap = map;
    }
    return newMap;
  }

  @SuppressWarnings("unchecked")
  private void copyInnerMap(List<String> fields, Map<String, Object> newMap,
      Entry<String, Object> entry) {

    ((Map<String, String>) entry.getValue())
        .entrySet()
        .stream()
        .filter(innerEntry -> fields.contains(innerEntry.getKey().toLowerCase()))
        .forEach(innerEntry -> {
          String value = innerEntry.getValue();

          Map<String, String> newInnerMap = (Map<String, String>) newMap.get(entry.getKey());
          if (newInnerMap == null) {
            // The key does not exist -> initialize
            newInnerMap = new LinkedHashMap<>();
            newInnerMap.put(innerEntry.getKey(), value);
          }
          // Add the value to this key
          newInnerMap.put(innerEntry.getKey(), value);
          // Add the inner map to the outer one
          newMap.put(entry.getKey(), newInnerMap);
        });
  }

  private Map<String, Object> showSummary(Map<String, Object> map) {
    Map<String, Object> newMap = new LinkedHashMap<>();
    map.entrySet()
        .forEach(entry -> {
          @SuppressWarnings("unchecked")
          Map<String, Object> value = (Map<String, Object>) entry.getValue();
          String summaryValue = (String) value.get(ACCESS_LEVEL_SUMMARY);
          newMap.put(entry.getKey(), summaryValue);
        });
    return newMap;
  }

  @SuppressWarnings("unchecked")
  private Set<String> convertToLowerCase(Entry<String, Object> entry) {
    return ((Map<String, String>) entry.getValue())
        .keySet()
        .stream()
        .map(key -> key.toLowerCase())
        .collect(Collectors.toSet());
  }

}
