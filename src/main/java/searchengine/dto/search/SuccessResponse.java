package searchengine.dto.search;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = false)
public class SuccessResponse extends SearchResponse{
    private int count;
    private List<DetailedSearchItem> data;
}
