package com.expenseflow.mockbank.fault;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Runtime fault switch used by SRE drills and the live demo. */
@RestController
@RequestMapping("/admin/faults")
public class FaultAdminController {

    private final FaultInjector injector;

    public FaultAdminController(FaultInjector injector) {
        this.injector = injector;
    }

    @GetMapping
    public FaultConfig get() {
        return injector.current();
    }

    @PostMapping
    public FaultConfig set(@Valid @RequestBody FaultConfig config) {
        return injector.update(config);
    }

    /** Back to the env defaults. */
    @DeleteMapping
    public FaultConfig reset() {
        return injector.reset();
    }
}
