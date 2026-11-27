package com.aimanthan.expenseflow;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ExpenseflowApplication {

	static {
		// All timestamps are stored and computed in UTC. Also fixes Windows laptops in India, whose JVM
		// default zone "Asia/Calcutta" is rejected by PostgreSQL 18 at connection time.
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
	}

	public static void main(String[] args) {
		SpringApplication.run(ExpenseflowApplication.class, args);
	}

}
