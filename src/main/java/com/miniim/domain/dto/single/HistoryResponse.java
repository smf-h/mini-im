package com.miniim.domain.dto.single;

import lombok.Data;
import java.util.List;

@Data
public class HistoryResponse {
    private List<MessageDTO> items;
    private Long nextCursor;
    private boolean hasMore;
}
