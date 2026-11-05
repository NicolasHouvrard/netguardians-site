package com.logicielpourtous.site.features.api.angular;

import com.logicielpourtous.site.features.pages.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/intra/menu")
public class MenuApiController {

    private final MenuItemRepository    menuItemRepository;
    private final PublicPageRepository  publicPageRepository;

    public MenuApiController(MenuItemRepository menuItemRepository,
                             PublicPageRepository publicPageRepository) {
        this.menuItemRepository   = menuItemRepository;
        this.publicPageRepository = publicPageRepository;
    }

    // ── DTOs ─────────────────────────────────────────────────────────────────

    public record MenuItemDto(
            Long id, String label, String url,
            Long linkedPageId, String linkedPageSlug, String linkedPageTitle,
            Long parentId, String position, String target,
            int sortOrder, boolean active,
            List<MenuItemDto> children
    ) {
        static MenuItemDto from(MenuItem m) {
            List<MenuItemDto> kids = m.getChildren() == null ? List.of()
                    : m.getChildren().stream().map(MenuItemDto::from).toList();
            return new MenuItemDto(
                    m.getId(), m.getLabel(), m.getUrl(),
                    m.getLinkedPage() != null ? m.getLinkedPage().getId() : null,
                    m.getLinkedPage() != null ? m.getLinkedPage().getSlug() : null,
                    m.getLinkedPage() != null ? m.getLinkedPage().getTitle() : null,
                    m.getParent() != null ? m.getParent().getId() : null,
                    m.getPosition() != null ? m.getPosition().name() : "MAIN",
                    m.getTarget() != null ? m.getTarget() : "_self",
                    m.getSortOrder(), m.isActive(), kids
            );
        }
    }

    public record PageLinkDto(Long id, String title, String slug) {}

    public record SaveMenuItemRequest(
            String label, String url, Long linkedPageId,
            Long parentId, String position, String target, boolean active
    ) {}

    public record OrderItem(Long id, Long parentId, int sortOrder) {}

    // ── GET /api/intra/menu?position=MAIN ────────────────────────────────────

    @GetMapping
    public List<MenuItemDto> list(@RequestParam(defaultValue = "MAIN") String position) {
        MenuPosition pos = parsePos(position);
        return menuItemRepository
                .findByPositionAndParentIsNullAndActiveTrueOrderBySortOrderAsc(pos)
                .stream().map(MenuItemDto::from).toList();
    }

    @GetMapping("/all")
    public List<MenuItemDto> listAll(@RequestParam(defaultValue = "MAIN") String position) {
        MenuPosition pos = parsePos(position);
        return menuItemRepository.findAll().stream()
                .filter(m -> pos.equals(m.getPosition()) && m.getParent() == null)
                .sorted(Comparator.comparingInt(MenuItem::getSortOrder))
                .map(MenuItemDto::from).toList();
    }

    // ── GET /api/intra/menu/pages ─────────────────────────────────────────────

    @GetMapping("/pages")
    public List<PageLinkDto> pages() {
        return publicPageRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(p -> new PageLinkDto(p.getId(), p.getTitle(), p.getSlug()))
                .toList();
    }

    // ── POST /api/intra/menu/items ────────────────────────────────────────────

    @PostMapping("/items")
    public ResponseEntity<MenuItemDto> create(@RequestBody SaveMenuItemRequest req) {
        MenuItem item = new MenuItem();
        applyRequest(item, req);
        MenuPosition itemPos = item.getPosition();
        long siblings = menuItemRepository.findAll().stream()
                .filter(m -> Objects.equals(
                        m.getParent() == null ? null : m.getParent().getId(),
                        req.parentId()) && itemPos.equals(m.getPosition()))
                .count();
        item.setSortOrder((int) siblings);
        return ResponseEntity.ok(MenuItemDto.from(menuItemRepository.save(item)));
    }

    // ── PUT /api/intra/menu/items/{id} ────────────────────────────────────────

    @PutMapping("/items/{id}")
    public ResponseEntity<MenuItemDto> update(@PathVariable Long id,
                                              @RequestBody SaveMenuItemRequest req) {
        return menuItemRepository.findById(id)
                .<ResponseEntity<MenuItemDto>>map(item -> {
                    applyRequest(item, req);
                    return ResponseEntity.ok(MenuItemDto.from(menuItemRepository.save(item)));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ── DELETE /api/intra/menu/items/{id} ─────────────────────────────────────

    @DeleteMapping("/items/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!menuItemRepository.existsById(id)) return ResponseEntity.notFound().build();
        menuItemRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // ── PUT /api/intra/menu/order ─────────────────────────────────────────────

    @PutMapping("/order")
    public ResponseEntity<Void> saveOrder(@RequestBody List<OrderItem> order) {
        for (OrderItem o : order) {
            menuItemRepository.findById(o.id()).ifPresent(item -> {
                item.setSortOrder(o.sortOrder());
                if (o.parentId() != null) {
                    menuItemRepository.findById(o.parentId()).ifPresent(item::setParent);
                } else {
                    item.setParent(null);
                }
                menuItemRepository.save(item);
            });
        }
        return ResponseEntity.ok().build();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private MenuPosition parsePos(String position) {
        try { return MenuPosition.valueOf(position.toUpperCase()); }
        catch (IllegalArgumentException e) { return MenuPosition.MAIN; }
    }

    private void applyRequest(MenuItem item, SaveMenuItemRequest req) {
        item.setLabel(req.label());
        item.setUrl(req.url());
        item.setActive(req.active());
        item.setTarget(req.target() != null ? req.target() : "_self");
        item.setPosition(req.position() != null ? parsePos(req.position()) : MenuPosition.MAIN);
        item.setLinkedPage(req.linkedPageId() != null
                ? publicPageRepository.findById(req.linkedPageId()).orElse(null)
                : null);
        item.setParent(req.parentId() != null
                ? menuItemRepository.findById(req.parentId()).orElse(null)
                : null);
    }
}
