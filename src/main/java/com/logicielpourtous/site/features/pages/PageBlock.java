package com.logicielpourtous.site.features.pages;

import jakarta.persistence.*;

@Entity
@Table(name = "page_blocks", schema = "public")
public class PageBlock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "page_id", nullable = false)
    private PublicPage page;

    @Enumerated(EnumType.STRING)
    @Column(name = "block_type", nullable = false)
    private BlockType blockType;

    @Column(columnDefinition = "TEXT")
    private String content = "{}";

    @Column(name = "sort_order")
    private int sortOrder = 0;

    // Getters & Setters

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public PublicPage getPage() { return page; }
    public void setPage(PublicPage page) { this.page = page; }

    public BlockType getBlockType() { return blockType; }
    public void setBlockType(BlockType blockType) { this.blockType = blockType; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
}
