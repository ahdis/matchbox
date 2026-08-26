package ca.uhn.fhir.jpa.model.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Formula;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.io.Serializable;
import java.util.Objects;

/**
 * This table contains the list of StructureDefinitions currently installed in Matchbox.
 * It is used to quickly list validatable StructureDefinitions without having to reparse all StructureDefinitions 
 * from the JSON blob stored in the NpmPackageVersionResourceEntity table.
 */
@Entity()
@Table(
  name = "MB_INSTALLED_STRUCT_DEF",
  indexes = {
    @Index(name = "IDX_IS_VALIDATABLE", columnList = "IS_VALIDATABLE"),
  })
public class MbInstalledStructureDefinitionEntity implements Serializable {

  /**
   * The maximum length of {@link #title}.
   */
  public static final int TITLE_MAX_LENGTH = 300;

  /**
   * A primary key for the table.
   */
  @Id
  @SequenceGenerator(name = "SEQ_MB_INSTSTRUCTDEF", sequenceName = "SEQ_MB_INSTSTRUCTDEF", allocationSize = 1)
  @GeneratedValue(strategy = GenerationType.AUTO, generator = "SEQ_MB_INSTSTRUCTDEF")
  @Column(name = "PID")
  private Long id;

  /**
   * StructureDefinition.url
   */
  @Column(name = "CANONICAL_URL", length = 500, nullable = false)
  private String canonicalUrl;

  /**
   * StructureDefinition.title or StructureDefinition.name
   */
  @Column(name = "TITLE", length = TITLE_MAX_LENGTH, nullable = false)
  private String title;

  /**
   * ImplementationGuide.packageId
   */
  @Column(name = "PACKAGE_ID", length = 200, nullable = false)
  private String packageId;

  /**
   * ImplementationGuide.version
   */
  @Column(name = "PACKAGE_VERSION", length = 200, nullable = false)
  private String packageVersion;

  /**
   * StructureDefinition.type
   */
  @Column(name = "TYPE", length = 100, nullable = false)
  private String type;

  /**
   * StructureDefinition.kind: primitive-type | complex-type | resource | logical
   */
  @Column(name = "KIND", length = 20, nullable = false)
  private String kind;

  /**
   * Whether the package version is the current one (i.e. the most recent one) or not.
   * <p>
   * This is computed live from NPM_PACKAGE_VER.CURRENT_VERSION on every read, instead of being copied at row
   * creation time: that flag can flip after this row was created (e.g. a newer version of the same package gets
   * installed, or the current version gets uninstalled), and a copy would go stale since nothing else in this
   * table would ever be notified of that change. This should be fast, only using indexed data.
   */
  @Formula("(SELECT npv.CURRENT_VERSION FROM NPM_PACKAGE_VER_RES npr "
    + "INNER JOIN NPM_PACKAGE_VER npv ON npv.PID = npr.PACKVER_PID "
    + "WHERE npr.PID = NPM_PACKAGE_VER_RES_ID)")
  private Boolean isCurrent;

  /**
   * Whether that StructureDefinition can be used for validation or not.
   */
  @Column(name = "IS_VALIDATABLE", nullable = false)
  private Boolean isValidatable;

  /**
   * A version number for this row's data, used to detect rows that need a migration to run against them.
   * <p>
   * Modeled as a Java {@code byte} so that Hibernate maps it to a native {@code TINYINT} column on H2 and {@code 
   * SMALLINT} on PostgreSQL.
   */
  @Column(name = "META_VERSION", nullable = false)
  @ColumnDefault("1")
  private byte metaVersion = 1;

  /**
   * The code of the document's composition type (e.g. Composition.type), if this StructureDefinition profiles a 
   * document. Null for non-document StructureDefinitions.
   */
  @Column(name = "DOC_COMP_TYPE_CODE", length = 200, nullable = true)
  private String docCompTypeCode;

  /**
   * The code of the document's composition category (e.g. Composition.category), if this StructureDefinition
   * profiles a document. Null for non-document StructureDefinitions.
   */
  @Column(name = "DOC_COMP_CAT_CODE", length = 200, nullable = true)
  private String docCompCatCode;

  /**
   * We keep a link to the original entity and cascade changes.
   * Like that, if it gets removed, this entity will also be removed.
   */
  @OneToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "NPM_PACKAGE_VER_RES_ID", referencedColumnName = "PID")
  @OnDelete(action = OnDeleteAction.CASCADE)
  private NpmPackageVersionResourceEntity npmPackageVersionResourceEntity;

  public Long getId() {
    return this.id;
  }

  public void setId(final Long id) {
    this.id = id;
  }

  public String getCanonicalUrl() {
    return this.canonicalUrl;
  }

  public void setCanonicalUrl(final String canonicalUrl) {
    this.canonicalUrl = canonicalUrl;
  }

  public String getTitle() {
    return this.title;
  }

  public void setTitle(final String title) {
    this.title = title;
  }

  public String getPackageId() {
    return this.packageId;
  }

  public void setPackageId(final String packageId) {
    this.packageId = packageId;
  }

  public String getPackageVersion() {
    return this.packageVersion;
  }

  public void setPackageVersion(final String packageVersion) {
    this.packageVersion = packageVersion;
  }

  public String getType() {
    return this.type;
  }

  public void setType(final String type) {
    this.type = type;
  }

  public String getKind() {
    return this.kind;
  }

  public void setKind(final String kind) {
    this.kind = kind;
  }

  public Boolean isCurrent() {
    return this.isCurrent;
  }

  public Boolean isValidatable() {
    return this.isValidatable;
  }

  public void setValidatable(final Boolean validatable) {
    isValidatable = validatable;
  }

  public byte getMetaVersion() {
    return this.metaVersion;
  }

  public void setMetaVersion(final byte metaVersion) {
    this.metaVersion = metaVersion;
  }

  public String getDocCompTypeCode() {
    return this.docCompTypeCode;
  }

  public void setDocCompTypeCode(final String docCompTypeCode) {
    this.docCompTypeCode = docCompTypeCode;
  }

  public String getDocCompCatCode() {
    return this.docCompCatCode;
  }

  public void setDocCompCatCode(final String docCompCatCode) {
    this.docCompCatCode = docCompCatCode;
  }

  public NpmPackageVersionResourceEntity getNpmPackageVersionResourceEntity() {
    return this.npmPackageVersionResourceEntity;
  }

  public void setNpmPackageVersionResourceEntity(final NpmPackageVersionResourceEntity npmPackageVersionResourceEntity) {
    this.npmPackageVersionResourceEntity = npmPackageVersionResourceEntity;
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) return true;
    if (!(o instanceof final MbInstalledStructureDefinitionEntity that)) return false;
    return id.equals(that.id)
      && canonicalUrl.equals(that.canonicalUrl)
      && title.equals(that.title)
      && packageId.equals(that.packageId)
      && packageVersion.equals(that.packageVersion)
      && type.equals(that.type)
      && kind.equals(that.kind)
      // isCurrent is formula-computed, so it's null until the entity is loaded from the database (e.g. still
      // unset on a freshly-constructed, not-yet-persisted instance): compare it null-safely.
      && Objects.equals(isCurrent, that.isCurrent)
      && isValidatable.equals(that.isValidatable)
      && metaVersion == that.metaVersion
      && Objects.equals(docCompTypeCode, that.docCompTypeCode)
      && Objects.equals(docCompCatCode, that.docCompCatCode);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, canonicalUrl, title, packageId, packageVersion, type, kind, isCurrent, isValidatable,
      metaVersion, docCompTypeCode, docCompCatCode);
  }

  @Override
  public String toString() {
    return "MbInstalledStructureDefinitionEntity{" +
      "id=" + id +
      ", canonicalUrl='" + canonicalUrl + '\'' +
      ", title='" + title + '\'' +
      ", packageId='" + packageId + '\'' +
      ", packageVersion='" + packageVersion + '\'' +
      ", type='" + type + '\'' +
      ", kind='" + kind + '\'' +
      ", isCurrent=" + isCurrent +
      ", isValidatable=" + isValidatable +
      ", metaVersion=" + metaVersion +
      ", docCompTypeCode='" + docCompTypeCode + '\'' +
      ", docCompCatCode='" + docCompCatCode + '\'' +
      '}';
  }
}