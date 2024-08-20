package org.henry.onlinebankingsystemp.service.impl;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.henry.onlinebankingsystemp.dto.DefaultApiResponse;
import org.henry.onlinebankingsystemp.dto.ViewBalanceDto;
import org.henry.onlinebankingsystemp.entity.Account;
import org.henry.onlinebankingsystemp.entity.Customer;
import org.henry.onlinebankingsystemp.repository.AccountRepository;
import org.henry.onlinebankingsystemp.repository.TokenRepository;
import org.henry.onlinebankingsystemp.repository.UserRepository;
import org.henry.onlinebankingsystemp.service.AccountService;
import org.henry.onlinebankingsystemp.service.JWTService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.henry.onlinebankingsystemp.constants.Constants.GET_BALANCE_SUCCESS;
import static org.henry.onlinebankingsystemp.constants.Constants.GET_DETAILS_SUCCESS;

@Slf4j @Service
@RequiredArgsConstructor
public class AccountServiceImpl implements AccountService {
    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final TokenRepository tokenRepository;
    private final JWTService jwtService;
    private final HttpServletRequest request;

    private String CUSTOMER_ACCESS_TOKEN(){
        return request.getHeader("Authorization").substring(7);
    }

    public DefaultApiResponse<Customer> getDetails() {
        verifyTokenExpiration(CUSTOMER_ACCESS_TOKEN());
        String userEmail = jwtService.extractUsername(CUSTOMER_ACCESS_TOKEN());
        DefaultApiResponse<Customer> apiResponse = new DefaultApiResponse<>();

        Customer customer = userRepository.findByEmail(userEmail).orElseThrow(
                () -> new IllegalStateException(String.format("Customer with email %s does not exist", userEmail)));

        apiResponse.setStatusCode(GET_DETAILS_SUCCESS);
        apiResponse.setStatusMessage("Customer details");
        apiResponse.setData(customer);

        return apiResponse;
    }

    @Override
    public DefaultApiResponse<ViewBalanceDto> checkBalance(){
//        verifyTokenExpiration(CUSTOMER_ACCESS_TOKEN());
        DefaultApiResponse<ViewBalanceDto> apiResponse = new DefaultApiResponse<>();
        String userEmail = jwtService.extractClaims(CUSTOMER_ACCESS_TOKEN(), Claims::getSubject);

        Customer existingCustomer;
        Account existingAccount;
        Optional<Customer> customer = userRepository.findByEmail(userEmail);

        try {
            if (customer.isPresent()) {
                existingCustomer = customer.get();
                Optional<Account> account = accountRepository.findAccountByCustomer_CustomerId(existingCustomer.getCustomerId());

                if (account.isPresent()) {
                    existingAccount = account.get();

                    apiResponse.setStatusCode(GET_BALANCE_SUCCESS);
                    apiResponse.setStatusMessage("Customer Balance");

                    String lastUpdatedAt = LocalDateTime.now().toString().replace("T", " ").substring(0, 16);

                    ViewBalanceDto balance = new ViewBalanceDto(
                            existingCustomer.getEmail(), existingAccount.getAccountNumber(),
                            existingAccount.getAccountBalance(), lastUpdatedAt
                    );
                    apiResponse.setData(balance);
                }
            }
        } catch (RuntimeException e) {
            log.error("An Error occurred while trying to fetch balance {} ", e.getMessage()); }
        return apiResponse;
    }

    private void verifyTokenExpiration(String token) {
        if (jwtService.isTokenExpired(token)) {
            throw new ExpiredJwtException(null, null, "Access Token has expired");
        }
    }



//    public DefaultApiResponse transferMoney(TransferDTO request) {
//        BalanceDto userBalance = new BalanceDto();
//        Transaction transaction = new Transaction();
//        DefaultApiResponse res = new DefaultApiResponse();
//
//        Customer customer = getCurrentUser.get();
//        Account userAccount = customer.getAccount();
//        String targetAccountNumber = request.getTargetAccountNumber();
//
//        Account targetAccount = getTarget(targetAccountNumber);
//        Customer targetCustomer = getDetails(targetAccount.getCustomerId());
//
//        if(request.getAmount().compareTo(BigDecimal.valueOf(200)) < 0){
//            res.setStatusCode(500);
//            res.setStatusMessage("Can't transfer less than 200 NGN");
//            return res;
//        }
//
//        if(request.getAmount().compareTo(customer.getAccount().getBalance()) > 0){
//            res.setStatusCode(500);
//            res.setStatusMessage("Insufficient Balance");
//            return res;
//        }
//
//        transaction.setCustomer(customer);
//        targetAccount.setBalance(targetCustomer.getAccount().getBalance().add(request.getAmount()));
//        transaction.setAccount(targetAccount);
//        transaction.setTransactionType(TransactionType.TRANSFER);
//        transaction.setTransactionDate(MillisToDateTime());
//        transaction.setTargetAccountNumber(String.valueOf(request.getAmount()));
//        transaction.setAmount(request.getAmount());
//        transaction.setDebit(request.getAmount());
//        transaction.setCredit(null);
//        transaction.setRunningBalance(request.getAmount());
//        transaction.setTransactionRef(generator.generateReference());
//        userAccount.setBalance(customer.getAccount().getBalance().subtract(request.getAmount()));
//
//        userBalance.setUsername(customer.getUsername());
//        userBalance.setBalance(customer.getAccount().getBalance().subtract(request.getAmount()));
//
//        accountRepository.save(userAccount);
//        accountRepository.save(targetAccount);
//        userRepository.save(customer);
//        userRepository.save(targetCustomer);
//        transactionRepository.save(transaction);
//
//        res.setStatusCode(200);
//        res.setStatusMessage("Transfer Successful");
//
//        return res;
//    }
//
//    public BigDecimal getDailyTransactionAmount(Long id) {
//        List<Transaction> transactions = transactionRepository.findTransactionByCustomer(id);
//        BigDecimal totalAmount = BigDecimal.valueOf(0.0);
//        for(Transaction tran : transactions){
//            if(tran.getTransactionType().equals(TransactionType.DEPOSIT)){
//                continue;
//            }
//            totalAmount = totalAmount.add(tran.getAmount());
//        }
//        return totalAmount;
//    }
//
//    public DefaultApiResponse updateBalance(TransactionDTO request, TransactionType transactionType, String operation){
//        DefaultApiResponse res = new DefaultApiResponse();
//        try {
//            BalanceDto userBalance = new BalanceDto();
//            Customer customer = getCurrentUser.get();
//
//            log.info("Comparing Balance and amount returned");
//            if(request.getAmount().compareTo(BigDecimal.ZERO) < 0){
//                res.setStatusCode(500);
//                res.setStatusMessage("Invalid amount");
//                return res;
//            }
//
//            int b1 = request.getAmount().compareTo(customer.getAccount().getBalance());
//            boolean b2 = request.getAmount().compareTo(customer.getAccount().getBalance()) == -1;
//            boolean b3 = request.getAmount().compareTo(customer.getAccount().getBalance()) < 0;
//            boolean b4 = request.getAmount().compareTo(customer.getAccount().getBalance()) > 0;
//            boolean b5 = request.getAmount().compareTo(customer.getAccount().getBalance()) == 1;
//
//            log.info("Checking for adequate balance");
//            if(request.getAmount().compareTo(customer.getAccount().getBalance()) != -1 && transactionType == TransactionType.WITHDRAWAL){
//                res.setStatusCode(500);
//                res.setStatusMessage("Insufficient Balance");
//                return res;
//            }
//
//            log.info("Performing Transaction Limit Check");
//            if(transactionType != TransactionType.DEPOSIT){
//                if(getDailyTransactionAmount(customer.getCustomerId()).add(request.getAmount()).compareTo(customer.getAccount().getTransactionLimit()) > 0){
//                    res.setStatusCode(500);
//                    res.setStatusMessage("You have exceeded your transaction limit for today");
//                    return res;
//                }
//            }
//
//            BigDecimal newBalance;
//            if(operation.equals("addition")){
//                newBalance = customer.getAccount().getBalance().add(request.getAmount());
//            }else
//                newBalance = customer.getAccount().getBalance().subtract(request.getAmount());
//
//            log.info("Updating the Database");
//            Account userAccount = customer.getAccount();
//            userAccount.setBalance(newBalance);
//
//            Transaction transaction = new Transaction();
//            transaction.setCustomer(customer);
//            transaction.setAccount(userAccount);
//            transaction.setTransactionType(transactionType);
//            transaction.setTransactionDate(MillisToDateTime());
//            transaction.setTargetAccountNumber(null);
//            transaction.setAmount(request.getAmount());
//            transaction.setBalanceAfterRunningBalance(newBalance);
//            if(transactionType.equals(TransactionType.DEPOSIT)){
//                transaction.setCredit(request.getAmount());
//            }else {
//                transaction.setDebit(request.getAmount());
//            }
//            transaction.setRunningBalance(request.getAmount());
//            transaction.setTransactionRef(generator.generateReference());
//
//
//            userBalance.setUsername(customer.getUsername());
//            userBalance.setBalance(newBalance);
//
//            accountRepository.save(userAccount);
//            userRepository.save(customer);
//            transactionRepository.save(transaction);
//
//            res.setStatusCode(200);
//            res.setStatusMessage(transactionType == TransactionType.WITHDRAWAL ? "Withdrawal Successful" : "Deposit Successful");
//
//            return res;
//        } catch (Exception e) {
//            res.setStatusCode(500);
//            res.setStatusMessage(e.getMessage());
//            return res;
//        }
//    }
//
//    public DefaultApiResponse depositMoney(TransactionDTO request){
//        return updateBalance(request, TransactionType.DEPOSIT, "addition");
//    }
//
//    public DefaultApiResponse withdrawMoney(TransactionDTO request){
//        return updateBalance(request, TransactionType.WITHDRAWAL, "subtract");
//    }
}
