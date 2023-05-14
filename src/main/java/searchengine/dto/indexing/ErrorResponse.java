package searchengine.dto.indexing;


import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = false)
public class ErrorResponse extends IndexingResponse{
    private boolean result = false;
    private String error;
}
