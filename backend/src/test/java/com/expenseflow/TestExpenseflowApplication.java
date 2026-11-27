package com.expenseflow;

import org.springframework.boot.SpringApplication;

public class TestExpenseflowApplication {

	public static void main(String[] args) {
		SpringApplication.from(ExpenseflowApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
