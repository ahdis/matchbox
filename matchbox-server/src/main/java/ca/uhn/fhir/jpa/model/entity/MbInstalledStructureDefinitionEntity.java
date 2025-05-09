package ca.uhn.fhir.jpa.model.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.io.Serializable;
import java.util.Objects;

/**
 * This table contains the list of StructureDefinitions currently installed in Matchbox.
 */
@Entity()
@Table(
	name = "MB_INSTALLED_STRUCT_DEF",
	indexes = {
		@Index(name = "IDX_IS_VALIDATABLE", columnList = "IS_VALIDATABLE"),
	})
public class MbInstalledStructureDefinitionEntity implements Serializable {

	/**
	 * A primary key for the table.
	 */
	@Id
	@SequenceGenerator(name = "SEQ_MB_INSTSTRUCTDEF", sequenceName = "SEQ_MB_INSTSTRUCTDEF")
	@GeneratedValue(strategy = GenerationType.AUTO, generator = "SEQ_MB_INSTSTRUCTDEF")
	@Column(name = "PID")
	private Long id;

	/**
	 * StructureDefinition.url
	 */
	@Column(name = "CANONICAL_URL", length = 200, nullable = false)
	private String canonicalUrl;

	/**
	 * StructureDefinition.title or StructureDefinition.name
	 */
	@Column(name = "TITLE", length = 200, nullable = false)
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
	 */
	@Column(name = "IS_CURRENT", nullable = false)
	private Boolean isCurrent;

	/**
	 * Whether that StructureDefinition can be used for validation or not.
	 */
	@Column(name = "IS_VALIDATABLE", nullable = false)
	private Boolean isValidatable;

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

	public void setCurrent(final Boolean current) {
		isCurrent = current;
	}

	public Boolean isValidatable() {
		return this.isValidatable;
	}

	public void setValidatable(final Boolean validatable) {
		isValidatable = validatable;
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
			&& isCurrent.equals(that.isCurrent)
			&& isValidatable.equals(that.isValidatable);
	}

	@Override
	public int hashCode() {
		return Objects.hash(id, canonicalUrl, title, packageId, packageVersion, type, kind, isCurrent, isValidatable);
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
			'}';
	}
}
