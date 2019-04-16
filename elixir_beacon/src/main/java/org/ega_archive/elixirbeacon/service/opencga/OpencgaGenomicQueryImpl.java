package org.ega_archive.elixirbeacon.service.opencga;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.NotImplementedException;
import org.ega_archive.elixirbeacon.dto.BeaconGenomicRegionResponse;
import org.ega_archive.elixirbeacon.dto.BeaconGenomicSnpResponse;
import org.ega_archive.elixirbeacon.dto.DatasetAlleleResponse;
import org.ega_archive.elixirbeacon.dto.Error;
import org.ega_archive.elixirbeacon.enums.ErrorCode;
import org.ega_archive.elixirbeacon.service.GenomicQuery;
import org.ega_archive.elixircore.event.sender.RestEventSender;
import org.opencb.commons.datastore.core.Query;
import org.opencb.commons.utils.ListUtils;
import org.opencb.opencga.client.exceptions.ClientException;
import org.opencb.opencga.client.rest.OpenCGAClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Primary
@Slf4j
@Service
public class OpencgaGenomicQueryImpl implements GenomicQuery {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private RestEventSender restEventSender;

    @Override
    public BeaconGenomicSnpResponse queryBeaconGenomicSnp(List<String> datasetStableIds, String alternateBases,
                                                          String referenceBases, String chromosome, Integer start, String referenceGenome,
                                                          String includeDatasetResponsesString, List<String> filters) {

        String variantId = chromosome + ":" + start.toString() + ":" + referenceBases + ":" + alternateBases;
        BeaconGenomicSnpResponse response = new BeaconGenomicSnpResponse();
        try {
            IncludeDatasetResponses includeDatasetResponses = parseIncludeDatasetResponses(includeDatasetResponsesString);
            List<DatasetAlleleResponse> datasetResponses = ListUtils.isEmpty(filters) ?
                    queryWithoutFilters(datasetStableIds, variantId, referenceGenome)
                    : queryWithFilters(datasetStableIds, variantId, referenceGenome, filters);
            response.setExists(datasetResponses.stream().anyMatch(datasetResponse -> datasetResponse.isExists()));
            response.setDatasetAlleleResponses(filterDatasetResults(datasetResponses, includeDatasetResponses));
        } catch (IOException | ClientException e) {
            Error error = new Error();
            error.setErrorCode(ErrorCode.GENERIC_ERROR);
            error.setMessage(e.getMessage());
            response.setError(error);
            return response;
        }
        return response;
    }

    @Override
    public BeaconGenomicRegionResponse queryBeaconGenomicRegion(List<String> datasetStableIds, String referenceBases,
                                                                String chromosome, Integer start, Integer end, String referenceGenome, String includeDatasetResponsesString,
                                                                List<String> filters) {
        // TODO Auto-generated method stub
        throw new NotImplementedException("queryBeaconGenomicRegion");
    }

    private List<DatasetAlleleResponse> filterDatasetResults(List<DatasetAlleleResponse> datasetResponses, IncludeDatasetResponses includeDatasetResponses) {
        switch (includeDatasetResponses) {
            case HIT:
                return datasetResponses.stream().filter(response -> null != response.getError() || response.isExists()).collect(Collectors.toList());
            case MISS:
                return datasetResponses.stream().filter(response -> null != response.getError() || !response.isExists()).collect(Collectors.toList());
            case NONE:
                return new ArrayList<>();
            // case ALL:
            default:
                return datasetResponses;
        }
    }

    private  List<DatasetAlleleResponse> queryWithoutFilters(List<String> datasetStableIds, String variantId, String referenceGenome) throws IOException, ClientException {
        Query query = new Query();
        query.put("id", variantId);
        // warning: we are assuming diploid chromosomes, with no haploid ones (Y?) nor polysomies
        query.put("includeGenotype", "0/1,1/1");
        query.put("summary", true);
        OpenCGAClient opencga = OpencgaUtils.getClient();
        BeaconSnpVisitorWithoutFilter visitor = new BeaconSnpVisitorWithoutFilter(opencga, query);
        StudyVisitor wrapper = new VisitorByDatasetId(datasetStableIds, visitor);
        wrapper = new VisitorByAssembly(referenceGenome, wrapper);
        OpencgaUtils.visitStudies(wrapper, opencga);
        return visitor.getResults();
    }

    private  List<DatasetAlleleResponse> queryWithFilters(List<String> datasetStableIds, String variantId, String referenceGenome, List<String> filters) throws IOException, ClientException {
        Filter filter = Filter.parse(filters);
        if (!filter.isValid()) {
            throw new IOException("invalid filter");
        } else {
            // warning: we are assuming diploid chromosomes, with no haploid ones (Y?) nor polysomies
            String genotypes = "0/1,1/1";
            Query query = new Query();
            query.put("id", variantId);
            query.put("genotypes", genotypes);
            query.put("all", false);
            OpencgaEnrichedClient opencga = OpencgaUtils.getClient();
            BeaconSnpVisitorWithFilter visitor = new BeaconSnpVisitorWithFilter(opencga, query, filter);
            StudyVisitor wrapper = new VisitorByDatasetId(datasetStableIds, visitor);
            wrapper = new VisitorByAssembly(referenceGenome, wrapper);
            OpencgaUtils.visitStudies(wrapper, opencga);
            return visitor.getResults();
        }
    }

    private static IncludeDatasetResponses parseIncludeDatasetResponses(String value) throws IOException {
        if (null == value) {
            return IncludeDatasetResponses.ALL;
        } else {
            switch(value.toLowerCase()) {
                case "all":
                     return IncludeDatasetResponses.ALL;
                case "hit":
                    return IncludeDatasetResponses.HIT;
                case "miss":
                    return IncludeDatasetResponses.MISS;
                case "none":
                    return IncludeDatasetResponses.NONE;
                default:
                    throw new IOException("invalid parameter: includeDatasetResponses");

            }
        }
    }

}