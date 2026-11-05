package com.logicielpourtous.site.features.request;

import org.springframework.beans.factory.annotation.Autowired;

import com.logicielpourtous.site.features.user.User;
import com.logicielpourtous.site.features.user.UserRepository;

import jakarta.transaction.Transactional;

public class RequestService {
    // Dans votre RequestService.java

@Autowired
private CommentRepository commentRepository;
@Autowired
private RequestRepository requestRepository; // Supposé exister
@Autowired
private UserRepository userRepository; // Supposé exister

@Transactional
public void addComment(Long requestId, String content, boolean isPublic, String authorEmail) {
    Request request = requestRepository.findById(requestId)
            .orElseThrow(() -> new RuntimeException("Demande non trouvée"));
    User author = userRepository.findByEmail(authorEmail)
            .orElseThrow(() -> new RuntimeException("Utilisateur non trouvé"));

    // Règle de sécurité : Seul le support peut poster un commentaire privé
    if (!isPublic) {
        boolean isSupport = author.getRoles().stream()
                                  .anyMatch(role -> role.getName().equals("ROLE_SUPPORT"));
        if (!isSupport) {
            throw new SecurityException("Seuls les membres du support peuvent poster des commentaires privés.");
        }
    }

    Comment comment = new Comment();
    comment.setContent(content);
    comment.setPublic(isPublic);
    comment.setAuthor(author);
    comment.setRequest(request);

    comment.registerAdded();
    commentRepository.save(comment);
}

}
