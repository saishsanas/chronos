package com.chronos.domain.account.command;

import com.chronos.domain.account.CorrectionDirection;
import com.chronos.domain.account.CorrectionType;
import java.util.UUID;

public sealed interface AccountCommand permits
    CreateAccount,
    DepositMoney,
    WithdrawMoney,
    FreezeAccount,
    UnfreezeAccount,
    SetOverdraftLimit,
    SetTransactionLimit,
    IssueCorrection,
    CloseAccount {

    UUID accountId();
}
