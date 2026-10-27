package com.example.navan.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.navan.common.Pagination.Page;
import com.example.navan.transaction.Transaction;
import com.example.navan.transaction.TransactionRequest.Create;
import com.example.navan.transaction.TransactionRequest.Patch;
import com.example.navan.transaction.TransactionService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/transactions")
class TransactionController {

	private final TransactionService service;

	TransactionController(TransactionService service) {
		this.service = service;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	Transaction create(@Valid @RequestBody Create request) {
		return service.create(request);
	}

	@GetMapping("/{id}")
	Transaction get(@PathVariable String id) {
		return service.get(id);
	}

	@GetMapping
	Page<Transaction> list(@RequestParam(required = false) Integer limit,
			@RequestParam(required = false) Integer offset) {
		return service.list(limit, offset);
	}

	@PatchMapping("/{id}")
	Transaction patch(@PathVariable String id, @Valid @RequestBody Patch patch) {
		return service.patch(id, patch);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void delete(@PathVariable String id) {
		service.delete(id);
	}
}
