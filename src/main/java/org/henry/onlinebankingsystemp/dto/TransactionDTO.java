package org.henry.onlinebankingsystemp.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import org.henry.onlinebankingsystemp.dto.enums.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Date;

@Data
@JsonIgnoreProperties
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TransactionDTO {
    private BigDecimal amount;
    private String targetAccountNumber;
    private LocalDateTime dateTime;
}
