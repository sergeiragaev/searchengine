package searchengine.model;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;
import java.time.LocalDateTime;
import java.util.List;

@Entity(name = "site")
@Getter
@Setter
public class SiteEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "ENUM('INDEXING', 'INDEXED', 'FAILED') NOT NULL")
    private StatusType status;

    @Column(nullable = false)
    private LocalDateTime statusTime;

    @Column(columnDefinition = "TEXT")
    private String lastError;

    @Column(columnDefinition = "VARCHAR(255) NOT NULL")
    private String url;

    @Column(columnDefinition = "VARCHAR(255) NOT NULL")
    private String name;

    @OneToMany(mappedBy = "site", cascade = CascadeType.ALL)
    private List<PageEntity> pageEntities;

    @OneToMany(mappedBy = "site", cascade = CascadeType.ALL)
    private List<LemmaEntity> lemmaEntities;

}
