package vn.tuanthongma.ieltsreading.content;

import java.io.IOException;
import java.security.Principal;
import java.util.List;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/admin/pdf-imports")
class AdminPdfImportController {
    private final PdfImportService imports;

    AdminPdfImportController(PdfImportService imports) {
        this.imports = imports;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<PdfImportService.Job> upload(@RequestPart MultipartFile file,
                                                @RequestParam String scope,
                                                @RequestParam(required = false) Integer partNumber,
                                                Principal principal) {
        PdfImportService.Job job = imports.upload(file, scope, partNumber, principal);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(HttpHeaders.LOCATION, "/api/admin/pdf-imports/" + job.id())
                .body(job);
    }

    @GetMapping
    List<PdfImportService.Job> list(Principal principal) {
        return imports.list(principal);
    }

    @GetMapping("/{id}")
    PdfImportService.Job get(@PathVariable long id, Principal principal) {
        return imports.get(id, principal);
    }

    @GetMapping("/{id}/source")
    ResponseEntity<FileSystemResource> source(@PathVariable long id, Principal principal) throws IOException {
        FileSystemResource resource = new FileSystemResource(imports.source(id, principal));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(resource.contentLength())
                .body(resource);
    }

    @GetMapping("/{id}/review")
    JsonNode review(@PathVariable long id, Principal principal) {
        return imports.review(id, principal);
    }

    @PostMapping("/{id}/retry")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.ACCEPTED)
    PdfImportService.Job retry(@PathVariable long id, Principal principal) {
        return imports.retry(id, principal);
    }

    @DeleteMapping("/{id}")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.ACCEPTED)
    PdfImportService.Job discard(@PathVariable long id, Principal principal) {
        return imports.discard(id, principal);
    }
}
