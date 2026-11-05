package com.logicielpourtous.site.features.chat;

import com.logicielpourtous.site.features.appointment.Appointment;
import com.logicielpourtous.site.features.appointment.AppointmentRepository;
import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.customers.CustomerRepository;
import com.logicielpourtous.site.features.request.Request;
import com.logicielpourtous.site.features.request.RequestPriority;
import com.logicielpourtous.site.features.request.RequestRepository;
import com.logicielpourtous.site.features.request.RequestStatus;
import com.logicielpourtous.site.features.user.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/intra/chat/quick")
public class ChatQuickActionController {

    private final RequestRepository      requestRepository;
    private final AppointmentRepository  appointmentRepository;
    private final CustomerRepository     customerRepository;
    private final UserRepository         userRepository;

    public ChatQuickActionController(RequestRepository requestRepository,
                                     AppointmentRepository appointmentRepository,
                                     CustomerRepository customerRepository,
                                     UserRepository userRepository) {
        this.requestRepository     = requestRepository;
        this.appointmentRepository = appointmentRepository;
        this.customerRepository    = customerRepository;
        this.userRepository        = userRepository;
    }

    /** Crée un ticket minimal depuis le chat. */
    @PostMapping("/ticket")
    public ResponseEntity<Map<String, Object>> quickTicket(
            @RequestParam String title,
            @RequestParam(required = false) String description,
            @RequestParam(required = false) Long customerId,
            Principal principal) {
        Request req = new Request();
        req.setTitle(title);
        req.setDescription(description);
        req.setStatus(RequestStatus.NEW);
        req.setPriority(RequestPriority.MEDIUM);
        if (customerId != null)
            customerRepository.findById(customerId).ifPresent(req::setCustomer);
        if (principal != null)
            userRepository.findByEmail(principal.getName()).ifPresent(req::setCreatedBy);
        req.registerChange();
        requestRepository.save(req);
        return ResponseEntity.ok(Map.of("id", req.getId(), "title", req.getTitle()));
    }

    /** Crée un rendez-vous minimal depuis le chat. */
    @PostMapping("/rdv")
    public ResponseEntity<Map<String, Object>> quickRdv(
            @RequestParam String title,
            @RequestParam String startAt,
            @RequestParam(required = false) Long customerId,
            @AuthenticationPrincipal CustomUserDetails current) {
        Appointment a = new Appointment();
        a.setTitle(title);
        a.setStartAt(LocalDateTime.parse(startAt));
        a.setType(Appointment.AppointmentType.ONSITE);
        if (customerId != null)
            customerRepository.findById(customerId).ifPresent(a::setCustomer);
        if (current != null)
            userRepository.findById(current.getUserId()).ifPresent(a::setAssignedTo);
        appointmentRepository.save(a);
        return ResponseEntity.ok(Map.of("id", a.getId(), "title", a.getTitle()));
    }
}
