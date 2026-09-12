package dev.remo.simplebackup.catalog;

import dev.remo.simplebackup.catalog.CatalogViews.PlanView;
import dev.remo.simplebackup.catalog.CatalogViews.SourceView;
import dev.remo.simplebackup.catalog.CatalogViews.TargetView;
import dev.remo.simplebackup.security.AuditService;
import jakarta.validation.Valid;
import java.net.URI;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Katalog-API.
 *
 * <p>Lesen darf jeder angemeldete Benutzer, alles Veraendernde bleibt beim Administrator --
 * das setzt die Sicherheitskonfiguration durch, hier steht es nicht noch einmal.
 */
@RestController
@RequestMapping("/api")
class CatalogController {

    private final CatalogService catalog;
    private final AuditService audit;

    CatalogController(CatalogService catalog, AuditService audit) {
        this.catalog = catalog;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- Quellen

    @GetMapping("/sources")
    List<SourceView> listSources() {
        return catalog.listSources();
    }

    @GetMapping("/sources/{id}")
    SourceView getSource(@PathVariable UUID id) {
        return catalog.getSource(id);
    }

    @PostMapping("/sources")
    ResponseEntity<SourceView> createSource(@Valid @RequestBody CatalogRequests.SaveSource request,
            Principal principal) {
        SourceView created = catalog.createSource(request);
        audit.record(principal.getName(), "SOURCE_CREATED", "backup_source", created.id().toString(), null);
        return ResponseEntity.created(URI.create("/api/sources/" + created.id())).body(created);
    }

    @PutMapping("/sources/{id}")
    SourceView updateSource(@PathVariable UUID id, @Valid @RequestBody CatalogRequests.SaveSource request,
            Principal principal) {
        SourceView updated = catalog.updateSource(id, request);
        audit.record(principal.getName(), "SOURCE_UPDATED", "backup_source", id.toString(), null);
        return updated;
    }

    @DeleteMapping("/sources/{id}")
    ResponseEntity<Void> deleteSource(@PathVariable UUID id, Principal principal) {
        catalog.deleteSource(id);
        audit.record(principal.getName(), "SOURCE_DELETED", "backup_source", id.toString(), null);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------ Ziele

    @GetMapping("/targets")
    List<TargetView> listTargets() {
        return catalog.listTargets();
    }

    @GetMapping("/targets/{id}")
    TargetView getTarget(@PathVariable UUID id) {
        return catalog.getTarget(id);
    }

    @PostMapping("/targets")
    ResponseEntity<TargetView> createTarget(@Valid @RequestBody CatalogRequests.SaveTarget request,
            Principal principal) {
        TargetView created = catalog.createTarget(request);
        audit.record(principal.getName(), "TARGET_CREATED", "backup_target", created.id().toString(), null);
        return ResponseEntity.created(URI.create("/api/targets/" + created.id())).body(created);
    }

    @PutMapping("/targets/{id}")
    TargetView updateTarget(@PathVariable UUID id, @Valid @RequestBody CatalogRequests.SaveTarget request,
            Principal principal) {
        TargetView updated = catalog.updateTarget(id, request);
        audit.record(principal.getName(), "TARGET_UPDATED", "backup_target", id.toString(), null);
        return updated;
    }

    @DeleteMapping("/targets/{id}")
    ResponseEntity<Void> deleteTarget(@PathVariable UUID id, Principal principal) {
        catalog.deleteTarget(id);
        audit.record(principal.getName(), "TARGET_DELETED", "backup_target", id.toString(), null);
        return ResponseEntity.noContent().build();
    }

    // ----------------------------------------------------------------- Plaene

    @GetMapping("/plans")
    List<PlanView> listPlans() {
        return catalog.listPlans();
    }

    @GetMapping("/plans/{id}")
    PlanView getPlan(@PathVariable UUID id) {
        return catalog.getPlan(id);
    }

    @PostMapping("/plans")
    ResponseEntity<PlanView> createPlan(@Valid @RequestBody CatalogRequests.SavePlan request,
            Principal principal) {
        PlanView created = catalog.createPlan(request);
        audit.record(principal.getName(), "PLAN_CREATED", "backup_plan", created.id().toString(), null);
        return ResponseEntity.created(URI.create("/api/plans/" + created.id())).body(created);
    }

    @PutMapping("/plans/{id}")
    PlanView updatePlan(@PathVariable UUID id, @Valid @RequestBody CatalogRequests.SavePlan request,
            Principal principal) {
        PlanView updated = catalog.updatePlan(id, request);
        audit.record(principal.getName(), "PLAN_UPDATED", "backup_plan", id.toString(), null);
        return updated;
    }

    @DeleteMapping("/plans/{id}")
    ResponseEntity<Void> deletePlan(@PathVariable UUID id, Principal principal) {
        catalog.deletePlan(id);
        audit.record(principal.getName(), "PLAN_DELETED", "backup_plan", id.toString(), null);
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------- Aufbewahrung

    @GetMapping("/retention-policies")
    List<RetentionPolicySummary> listRetentionPolicies() {
        return catalog.listRetentionPolicies().stream()
                .map(policy -> new RetentionPolicySummary(policy.getId(), policy.getName(),
                        policy.toRule()))
                .toList();
    }

    @PostMapping("/retention-policies")
    ResponseEntity<RetentionPolicySummary> createRetentionPolicy(
            @Valid @RequestBody CatalogRequests.SaveRetentionPolicy request, Principal principal) {

        RetentionPolicy created = catalog.createRetentionPolicy(request);
        audit.record(principal.getName(), "RETENTION_POLICY_CREATED", "retention_policy",
                created.getId().toString(), null);

        var summary = new RetentionPolicySummary(created.getId(), created.getName(), created.toRule());
        return ResponseEntity.created(URI.create("/api/retention-policies/" + created.getId())).body(summary);
    }

    @DeleteMapping("/retention-policies/{id}")
    ResponseEntity<Void> deleteRetentionPolicy(@PathVariable UUID id, Principal principal) {
        catalog.deleteRetentionPolicy(id);
        audit.record(principal.getName(), "RETENTION_POLICY_DELETED", "retention_policy",
                id.toString(), null);
        return ResponseEntity.noContent().build();
    }

    record RetentionPolicySummary(UUID id, String name,
            dev.remo.simplebackup.shared.RetentionRule rule) {
    }
}
