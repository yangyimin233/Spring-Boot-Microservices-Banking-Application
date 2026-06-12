package org.training.account.service.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.training.account.service.model.AccountStatus;
import org.training.account.service.model.AccountType;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long accountId;

    private String accountNumber;

    @Enumerated(EnumType.STRING)
    private AccountType accountType;

    @Enumerated(EnumType.STRING)
    private AccountStatus accountStatus;

    @CreationTimestamp
    private LocalDate openingDate;

    /** 缓存余额（非实时，由分录表定期重算刷新） */
    private BigDecimal availableBalance;

    /** 实时分录余额（不存库，从 transaction-service 分录表实时计算） */
    @Transient
    private BigDecimal ledgerBalance;

    private Long userId;
}
