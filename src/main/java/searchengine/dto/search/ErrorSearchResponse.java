package searchengine.dto.search;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = false)
public class ErrorSearchResponse extends SearchResponse{
    private String error;
    private boolean result;
}
