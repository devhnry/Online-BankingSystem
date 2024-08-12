package org.henry.onlinebankingsystemp.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.henry.onlinebankingsystemp.dto.*;
import org.henry.onlinebankingsystemp.dto2.*;
import org.henry.onlinebankingsystemp.entity.OneTimePassword;
import org.henry.onlinebankingsystemp.enums.TransactionType;
import org.henry.onlinebankingsystemp.entity.Account;
import org.henry.onlinebankingsystemp.entity.Customer;
import org.henry.onlinebankingsystemp.entity.Transaction;
import org.henry.onlinebankingsystemp.repository.AccountRepository;
import org.henry.onlinebankingsystemp.repository.OTPRepository;
import org.henry.onlinebankingsystemp.repository.TransactionRepo;
import org.henry.onlinebankingsystemp.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Supplier;


@Service
@Slf4j
@RequiredArgsConstructor
public class UserService {
    @Autowired
    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final TransactionRepo transactionRepo;
    private final PasswordEncoder passwordEncoder;
    private final OTPRepository otpRepository;


    private Long getUserId(){
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return ((Customer) authentication.getPrincipal()).getCustomerId();
    }

    private Customer getDetails(Long id) {
        return userRepository.findById(id).orElseThrow(
                () -> new IllegalStateException("Customer with id " + id + "does not exist"));
    }

    Supplier<Customer> getCurrentUser = () -> { Long id = getUserId(); Customer customer = getDetails(id);
        return customer;
    };


    public BalanceDto getBalance(){
        BalanceDto userBalance = new BalanceDto();

        Customer customer = getCurrentUser.get();
        Optional<Account> optAccount = accountRepository.findByCustomerId(customer.getCustomerId());
        Account account = optAccount.orElseThrow();

        userBalance.setUsername(customer.getUsername());
        userBalance.setBalance(account.getBalance());

        return userBalance;
    }

    public List<TransactionDTO> viewStatement(){
        Long id = getUserId();
        List<TransactionDTO> dtos = mapTransactionsToDTOs(transactionRepo.findTransactionByCustomer(id));
        return dtos;
    }

    public List<TransactionDTO> mapTransactionsToDTOs(List<Transaction> transactions) {
        List<TransactionDTO> transactionList = new ArrayList<>();
        for (Transaction transaction : transactions) {
            TransactionDTO dto = new TransactionDTO();
            dto.setAmount(transaction.getAmount());
            dto.setDateTime(transaction.getTransactionDate());
            if (transaction.getTransactionType() == TransactionType.TRANSFER) {
                dto.setTargetAccountNumber(transaction.getTargetAccountNumber());
            }
            transactionList.add(dto);
        }
        return transactionList;
    }

    public OneTimePassword generateOTP(){
        OneTimePassword oneTimePassword = new OneTimePassword();
        Customer currentCustomer = getCurrentUser.get();

        oneTimePassword.setOtpCode(new Random().nextLong(100000L));
        oneTimePassword.setCustomer(currentCustomer);
        oneTimePassword.setExpired(false);
        oneTimePassword.setExpiresIn("4 Minutes");

        long expirationTime = 240000;

        long currentTime = System.currentTimeMillis();
        oneTimePassword.setGeneratedTime(currentTime);

        long expirationTimestamp = currentTime + expirationTime;
        oneTimePassword.setExpirationTime(expirationTimestamp);

        otpRepository.save(oneTimePassword);
        return oneTimePassword;
    }


    private String validateOTP(Long otpCode) {
        String message;
        Customer customer = getCurrentUser.get();

        log.info("Finding Customer by OtpCode");
        Optional<OneTimePassword> otpOptional = otpRepository.findByCustomerAndOtpCode(customer, otpCode);
        if (otpOptional.isPresent()) {
            log.info("Customer was found with OTPCode");
            OneTimePassword oneTimePassword = otpOptional.get();
            long currentTime = System.currentTimeMillis();
            log.info("Checking OneTimePassword expiration time");
            if (oneTimePassword.getExpirationTime() - currentTime < 0 || oneTimePassword.getExpired()) {
                message = "expired";
            } else {
                message = "valid";
            }
        } else {
            message = "invalid";
        }

        return message;
    }

    @Transactional
    public DefaultApiResponse updateDetails(UpdateInfoDTO req){
        DefaultApiResponse response = new DefaultApiResponse();
        Customer customer = getCurrentUser.get();

        Optional<OneTimePassword> otpOptional = otpRepository.findByCustomerAndOtpCode(customer, req.getOtpCode());
        var otp = otpOptional.orElseThrow();


        String otpMessage = validateOTP(req.getOtpCode());
        if(otpMessage.equals("invalid")){
            response.setStatusCode(500);
            response.setStatusMessage("Invalid OneTimePassword Code");

            return response;
        }else if(otpMessage.equals("expired")){
            response.setStatusMessage("Expired OneTimePassword Code");
            response.setStatusCode(500);
            return response;
        }

        Optional<Customer> usersOptional =
                userRepository.findByEmail(req.getEmail());

        if (usersOptional.isPresent()){
            response.setStatusCode(500);
            response.setStatusMessage("Email Already Taken");
            return response;
        }

        customer.setFirstName(req.getFirstName() == null ? customer.getFirstName() : req.getFirstName());
        customer.setLastName(req.getLastName() == null ? customer.getLastName() : req.getLastName());
        customer.setEmail(req.getEmail() == null ? customer.getEmail() : req.getEmail());
        customer.setPhone(req.getPhoneNumber() == null ? customer.getPhone() : req.getPhoneNumber());

        response.setStatusCode(200);
        response.setStatusMessage("Successfully Updated");
        otp.setExpired(true);

        return response;
    }

    private DefaultApiResponse validateOTP(String otpMessage, DefaultApiResponse res) {
        if(otpMessage.equals("invalid")){
            res.setStatusCode(500);
            res.setStatusMessage("Invalid OneTimePassword Credentials");
        }else if(otpMessage.equals("expired")){
            res.setStatusCode(500);
            res.setStatusMessage("Expired OneTimePassword");
        }
        return res;
    }

    @Transactional
    public DefaultApiResponse resetPassword(PasswordResetDto pass){
        DefaultApiResponse res = new DefaultApiResponse();
        Customer customer = getCurrentUser.get();

        Optional<OneTimePassword> otpOptional = otpRepository.findByCustomerAndOtpCode(customer, pass.getOtp());

        var otp = otpOptional.orElseThrow();
        log.info("Getting OneTimePassword Message");
        String otpMessage = validateOTP(pass.getOtp());

        log.info("Validating OneTimePassword");
        validateOTP(otpMessage, res);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String password = ((Customer) authentication.getPrincipal()).getPassword();

        if(passwordEncoder.matches(pass.getCurrentPassword(), password)){
            customer.setPassword(passwordEncoder.encode(pass.getNewPassword()));
            userRepository.save(customer);
            res.setStatusCode(200);
            res.setStatusMessage("Successfully updated Password");
            otp.setExpired(true);
        }else{
            res.setStatusCode(500);
            res.setStatusMessage("The current password is invalid");
            return res;
        }
        return res;
    }

    public DefaultApiResponse updateTransactionLimit(TransactionLimitDto transactionLimitDto){
        DefaultApiResponse res = new DefaultApiResponse();
        Customer customer = getCurrentUser.get();

        Optional<OneTimePassword> otpOptional = otpRepository.findByCustomerAndOtpCode(customer, transactionLimitDto.getOtpCode());

        var otp = otpOptional.orElseThrow();
        String otpMessage = validateOTP(transactionLimitDto.getOtpCode());
        validateOTP(otpMessage, res);

        if(transactionLimitDto.getAmount().compareTo(BigDecimal.ZERO) > 0){
            log.error("Updating Transaction Limit");
            res.setStatusCode(500);
            res.setStatusMessage("Amount cannot be Invalid");
        }

        customer.getAccount().setTransactionLimit(transactionLimitDto.getAmount());

        res.setStatusCode(200);
        res.setStatusMessage("Successfully Updated Transaction Limit");
        otp.setExpired(true);
        return res;
    }
}
