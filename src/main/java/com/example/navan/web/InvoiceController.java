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
import com.example.navan.invoice.Invoice;
import com.example.navan.invoice.Invoice.Request;
import com.example.navan.invoice.Invoice.Summary;
import com.example.navan.invoice.InvoiceService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/invoices")
class InvoiceController {

	private final InvoiceService service;

	InvoiceController(InvoiceService service) {
		this.service = service;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	Invoice create(@Valid @RequestBody Request request) {
		return service.create(request);
	}

	@GetMapping("/{id}")
	Invoice get(@PathVariable String id) {
		return service.get(id);
	}

	@GetMapping
	Page<Summary> list(@RequestParam(required = false) Integer limit,
			@RequestParam(required = false) Integer offset) {
		return service.list(limit, offset);
	}

	@PatchMapping("/{id}")
	Invoice patch(@PathVariable String id, @Valid @RequestBody Request request) {
		return service.replace(id, request);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void delete(@PathVariable String id) {
		service.delete(id);
	}
}
