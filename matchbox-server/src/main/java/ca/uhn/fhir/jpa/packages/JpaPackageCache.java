/*-
 * #%L
 * HAPI FHIR JPA Server
 * %%
 * Copyright (C) 2014 - 2025 Smile CDR, Inc.
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package ca.uhn.fhir.jpa.packages;

import ca.uhn.fhir.context.BaseRuntimeChildDefinition;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.FhirVersionEnum;
import ca.uhn.fhir.i18n.Msg;
import ca.uhn.fhir.interceptor.model.RequestPartitionId;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.api.model.ExpungeOptions;
import ca.uhn.fhir.jpa.binary.api.IBinaryStorageSvc;
import ca.uhn.fhir.jpa.binary.svc.NullBinaryStorageSvcImpl;
import ca.uhn.fhir.jpa.dao.data.INpmPackageDao;
import ca.uhn.fhir.jpa.dao.data.INpmPackageVersionDao;
import ca.uhn.fhir.jpa.dao.data.INpmPackageVersionResourceDao;
import ca.uhn.fhir.jpa.model.config.PartitionSettings;
import ca.uhn.fhir.jpa.model.dao.JpaPid;
import ca.uhn.fhir.jpa.model.entity.NpmPackageEntity;
import ca.uhn.fhir.jpa.model.entity.NpmPackageVersionEntity;
import ca.uhn.fhir.jpa.model.entity.NpmPackageVersionResourceEntity;
import ca.uhn.fhir.jpa.model.entity.ResourceTable;
import ca.uhn.fhir.jpa.model.util.JpaConstants;
import ca.uhn.fhir.jpa.packages.loader.NpmPackageData;
import ca.uhn.fhir.jpa.packages.loader.PackageLoaderSvc;
import ca.uhn.fhir.rest.api.Constants;
import ca.uhn.fhir.rest.api.EncodingEnum;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import ca.uhn.fhir.util.BinaryUtil;
import ca.uhn.fhir.util.ResourceUtil;
import ca.uhn.fhir.util.StringUtil;
import ch.ahdis.matchbox.packages.MatchboxJpaPackageCache;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.apache.commons.collections4.comparators.ReverseComparator;
import org.apache.commons.lang3.Validate;
import org.hl7.fhir.exceptions.FHIRException;
import org.hl7.fhir.instance.model.api.IBase;
import org.hl7.fhir.instance.model.api.IBaseBackboneElement;
import org.hl7.fhir.instance.model.api.IBaseBinary;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.instance.model.api.IPrimitiveType;
import org.hl7.fhir.utilities.npm.BasePackageCacheManager;
import org.hl7.fhir.utilities.npm.NpmPackage;
import org.hl7.fhir.utilities.npm.PackageServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static ca.uhn.fhir.jpa.util.QueryParameterUtils.toPredicateArray;
import static ca.uhn.fhir.util.StringUtil.toUtf8String;
import static org.apache.commons.lang3.StringUtils.defaultString;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

public class JpaPackageCache extends BasePackageCacheManager implements IHapiPackageCacheManager {

	public static final String UTF8_BOM = "\uFEFF";
	private static final Logger ourLog = LoggerFactory.getLogger(JpaPackageCache.class);
	private final Map<FhirVersionEnum, FhirContext> myVersionToContext = Collections.synchronizedMap(new HashMap<>());

	@PersistenceContext
	protected EntityManager myEntityManager;

	@Autowired
	private INpmPackageDao myPackageDao;

	@Autowired
	private INpmPackageVersionDao myPackageVersionDao;

	@Autowired
	private INpmPackageVersionResourceDao myPackageVersionResourceDao;

	@Autowired
	private DaoRegistry myDaoRegistry;

	@Autowired
	private FhirContext myCtx;

	@Autowired
	private PlatformTransactionManager myTxManager;

	@Autowired
	private PartitionSettings myPartitionSettings;

	@Autowired
	private PackageLoaderSvc myPackageLoaderSvc;

	@Autowired(required = false) // It is possible that some implementers will not create such a bean.
	private IBinaryStorageSvc myBinaryStorageSvc;

	@Override
	public void addPackageServer(@Nonnull PackageServer thePackageServer) {
		assert myPackageLoaderSvc != null;
		myPackageLoaderSvc.addPackageServer(thePackageServer);
	}

	@Override
	public String getPackageId(String theS) throws IOException {
		return myPackageLoaderSvc.getPackageId(theS);
	}

	@Override
	public String getPackageUrl(String theS) throws IOException {
		return myPackageLoaderSvc.getPackageUrl(theS);
	}

	@Override
	public List<PackageServer> getPackageServers() {
		return myPackageLoaderSvc.getPackageServers();
	}

	@Override
	protected BasePackageCacheManager.InputStreamWithSrc loadFromPackageServer(String id, String version) {
		throw new UnsupportedOperationException(Msg.code(2220) + "Use PackageLoaderSvc for loading packages.");
	}

	@Override
	@Transactional
	public NpmPackage loadPackageFromCacheOnly(String theId, @Nullable String theVersion) {
		Optional<NpmPackageVersionEntity> packageVersion = loadPackageVersionEntity(theId, theVersion);
		if (!packageVersion.isPresent() && theVersion.endsWith(".x")) {
			String lookupVersion = theVersion;
			do {
				lookupVersion = lookupVersion.substring(0, lookupVersion.length() - 2);
			} while (lookupVersion.endsWith(".x"));

			List<String> candidateVersionIds =
					myPackageVersionDao.findVersionIdsByPackageIdAndLikeVersion(theId, lookupVersion + ".%");
			if (candidateVersionIds.size() > 0) {
				candidateVersionIds.sort(PackageVersionComparator.INSTANCE);
				packageVersion =
						loadPackageVersionEntity(theId, candidateVersionIds.get(candidateVersionIds.size() - 1));
			}
		}

		return packageVersion.map(t -> loadPackage(t)).orElse(null);
	}

	private Optional<NpmPackageVersionEntity> loadPackageVersionEntity(String theId, @Nullable String theVersion) {
		Validate.notBlank(theId, "theId must be populated");

		Optional<NpmPackageVersionEntity> packageVersion = Optional.empty();
		if (isNotBlank(theVersion) && !"latest".equals(theVersion)) {
			packageVersion = myPackageVersionDao.findByPackageIdAndVersion(theId, theVersion);
		} else {
			Optional<NpmPackageEntity> pkg = myPackageDao.findByPackageId(theId);
			if (pkg.isPresent()) {
				packageVersion = myPackageVersionDao.findByPackageIdAndVersion(
						theId, pkg.get().getCurrentVersionId());
			}
		}
		return packageVersion;
	}

	private NpmPackage loadPackage(NpmPackageVersionEntity thePackageVersion) {
		PackageContents content = loadPackageContents(thePackageVersion);
		ByteArrayInputStream inputStream = new ByteArrayInputStream(content.getBytes());
		try {
			return NpmPackage.fromPackage(inputStream);
		} catch (IOException e) {
			throw new InternalErrorException(Msg.code(1294) + e);
		}
	}

	private IHapiPackageCacheManager.PackageContents loadPackageContents(NpmPackageVersionEntity thePackageVersion) {
		IFhirResourceDao<? extends IBaseBinary> binaryDao = getBinaryDao();
		IBaseBinary binary =
				binaryDao.readByPid(thePackageVersion.getPackageBinary().getId());
		try {
			byte[] content = fetchBlobFromBinary(binary);
			PackageContents retVal = new PackageContents()
					.setBytes(content)
					.setPackageId(thePackageVersion.getPackageId())
					.setVersion(thePackageVersion.getVersionId())
					.setLastModified(thePackageVersion.getUpdatedTime());
			return retVal;
		} catch (IOException e) {
			throw new InternalErrorException(
					Msg.code(1295) + "Failed to load package. There was a problem reading binaries", e);
		}
	}

	/**
	 * Helper method which will attempt to use the IBinaryStorageSvc to resolve the binary blob if available. If
	 * the bean is unavailable, fallback to assuming we are using an embedded base64 in the data element.
	 * @param theBinary the Binary who's `data` blob you want to retrieve
	 * @return a byte array containing the blob.
	 *
	 * @throws IOException
	 */
	private byte[] fetchBlobFromBinary(IBaseBinary theBinary) throws IOException {
		if (myBinaryStorageSvc != null && !(myBinaryStorageSvc instanceof NullBinaryStorageSvcImpl)) {
			return myBinaryStorageSvc.fetchDataByteArrayFromBinary(theBinary);
		} else {
			byte[] value = BinaryUtil.getOrCreateData(myCtx, theBinary).getValue();
			if (value == null) {
				throw new InternalErrorException(
						Msg.code(1296) + "Failed to fetch blob from Binary/" + theBinary.getIdElement());
			}
			return value;
		}
	}

	@SuppressWarnings("unchecked")
	private IFhirResourceDao<IBaseBinary> getBinaryDao() {
		return myDaoRegistry.getResourceDao("Binary");
	}

	private NpmPackage addPackageToCacheInternal(NpmPackageData thePackageData) {
		NpmPackage npmPackage = thePackageData.getPackage();
		String packageId = thePackageData.getPackageId();
		String initialPackageVersionId = thePackageData.getPackageVersionId();
		byte[] bytes = thePackageData.getBytes();

		if (!npmPackage.id().equalsIgnoreCase(packageId)) {
			throw new InvalidRequestException(
					Msg.code(1297) + "Package ID " + npmPackage.id() + " doesn't match expected: " + packageId);
		}
		if (!PackageVersionComparator.isEquivalent(initialPackageVersionId, npmPackage.version())) {
			throw new InvalidRequestException(Msg.code(1298) + "Package ID " + npmPackage.version()
					+ " doesn't match expected: " + initialPackageVersionId);
		}

		String packageVersionId = npmPackage.version();
		FhirVersionEnum fhirVersion = FhirVersionEnum.forVersionString(npmPackage.fhirVersion());
		if (fhirVersion == null) {
			throw new InvalidRequestException(Msg.code(1299) + "Unknown FHIR version: " + npmPackage.fhirVersion());
		}
		FhirContext packageContext = getFhirContext(fhirVersion);

		IBaseBinary binary = createPackageBinary(bytes);

		return newTxTemplate().execute(tx -> {
			ResourceTable persistedPackage = createResourceBinary(binary);
			NpmPackageEntity pkg = myPackageDao.findByPackageId(packageId).orElseGet(() -> createPackage(npmPackage));
			NpmPackageVersionEntity packageVersion = myPackageVersionDao
					.findByPackageIdAndVersion(packageId, packageVersionId)
					.orElse(null);
			if (packageVersion != null) {
				NpmPackage existingPackage =
						loadPackageFromCacheOnly(packageVersion.getPackageId(), packageVersion.getVersionId());
				String msg = "Package version already exists in local storage, no action taken: " + packageId + "#"
						+ packageVersionId;
				getProcessingMessages(existingPackage).add(msg);
				ourLog.info(msg);
				return existingPackage;
			}

			boolean currentVersion =
					updateCurrentVersionFlagForAllPackagesBasedOnNewIncomingVersion(packageId, packageVersionId);

			String packageDesc = truncateStorageString(npmPackage.description());
			String packageAuthor = truncateStorageString(npmPackage.getNpm().asString("author"));

			if (currentVersion) {
				getProcessingMessages(npmPackage)
						.add("Marking package " + packageId + "#" + initialPackageVersionId + " as current version");
				pkg.setCurrentVersionId(packageVersionId);
				pkg.setDescription(packageDesc);
				myPackageDao.save(pkg);
			} else {
				getProcessingMessages(npmPackage)
						.add("Package " + packageId + "#" + initialPackageVersionId + " is not the newest version");
			}

			packageVersion = new NpmPackageVersionEntity();
			packageVersion.setPackageId(packageId);
			packageVersion.setVersionId(packageVersionId);
			packageVersion.setPackage(pkg);
			packageVersion.setPackageBinary(persistedPackage);
			packageVersion.setSavedTime(new Date());
			packageVersion.setAuthor(packageAuthor);
			packageVersion.setDescription(packageDesc);
			packageVersion.setFhirVersionId(npmPackage.fhirVersion());
			packageVersion.setFhirVersion(fhirVersion);
			packageVersion.setCurrentVersion(currentVersion);
			packageVersion.setPackageSizeBytes(bytes.length);
			packageVersion = myPackageVersionDao.save(packageVersion);

			String dirName = "package";
			NpmPackage.NpmPackageFolder packageFolder = npmPackage.getFolders().get(dirName);
			Map<String, List<String>> packageFolderTypes = null;
			try {
				packageFolderTypes = packageFolder.getTypes();
			} catch (IOException e) {
				throw new InternalErrorException(Msg.code(2371) + e);
			}
			for (Map.Entry<String, List<String>> nextTypeToFiles : packageFolderTypes.entrySet()) {
				String nextType = nextTypeToFiles.getKey();
				for (String nextFile : nextTypeToFiles.getValue()) {

					byte[] contents;
					String contentsString;
					try {
						contents = packageFolder.fetchFile(nextFile);
						contentsString = toUtf8String(contents);
					} catch (IOException e) {
						throw new InternalErrorException(Msg.code(1300) + e);
					}

					IBaseResource resource;
					if (nextFile.toLowerCase().endsWith(".xml")) {
						resource = packageContext.newXmlParser().parseResource(contentsString);
					} else if (nextFile.toLowerCase().endsWith(".json")) {
						resource = packageContext.newJsonParser().parseResource(contentsString);
					} else {
						getProcessingMessages(npmPackage).add("Not indexing file: " + nextFile);
						continue;
					}

					/*
					 * Re-encode the resource as JSON with the narrative removed in order to reduce the footprint.
					 * This is useful since we'll be loading these resources back and hopefully keeping lots of
					 * them in memory in order to speed up validation activities.
					 */
					String contentType = Constants.CT_FHIR_JSON_NEW;
					ResourceUtil.removeNarrative(packageContext, resource);
					byte[] minimizedContents = packageContext
							.newJsonParser()
							.encodeResourceToString(resource)
							.getBytes(StandardCharsets.UTF_8);

					IBaseBinary resourceBinary = createPackageResourceBinary(nextFile, minimizedContents, contentType);
					ResourceTable persistedResource = createResourceBinary(resourceBinary);

					NpmPackageVersionResourceEntity resourceEntity = new NpmPackageVersionResourceEntity();
					resourceEntity.setPackageVersion(packageVersion);
					resourceEntity.setResourceBinary(persistedResource);
					resourceEntity.setDirectory(dirName);
					resourceEntity.setFhirVersionId(npmPackage.fhirVersion());
					resourceEntity.setFhirVersion(fhirVersion);
					resourceEntity.setFilename(nextFile);
					resourceEntity.setResourceType(nextType);
					resourceEntity.setResSizeBytes(contents.length);
					BaseRuntimeChildDefinition urlChild =
							packageContext.getResourceDefinition(nextType).getChildByName("url");
					BaseRuntimeChildDefinition versionChild =
							packageContext.getResourceDefinition(nextType).getChildByName("version");
					String url = null;
					String version = null;
					if (urlChild != null) {
						url = urlChild.getAccessor()
								.getFirstValueOrNull(resource)
								.map(t -> ((IPrimitiveType<?>) t).getValueAsString())
								.orElse(null);
						resourceEntity.setCanonicalUrl(url);

						Optional<IBase> resourceVersion =
								versionChild.getAccessor().getFirstValueOrNull(resource);
						if (resourceVersion.isPresent() && resourceVersion.get() instanceof IPrimitiveType) {
							version = ((IPrimitiveType<?>) resourceVersion.get()).getValueAsString();
						} else if (resourceVersion.isPresent()
								&& resourceVersion.get() instanceof IBaseBackboneElement) {
							version = String.valueOf(myCtx.newFhirPath()
									.evaluateFirst(resourceVersion.get(), "value", IPrimitiveType.class)
									.orElse(null));
						} else {
							version = null;
						}
						resourceEntity.setCanonicalVersion(version);
					}
          // PATCH MATCHBOX: the next line is our customization hook: https://github.com/ahdis/matchbox/issues/341
          MatchboxJpaPackageCache.customizeNpmPackageVersionResourceEntity(resourceEntity, resource);
					myPackageVersionResourceDao.save(resourceEntity);

					String resType = packageContext.getResourceType(resource);
					String msg = "Indexing " + resType + " Resource[" + dirName + '/' + nextFile + "] with URL: "
							+ defaultString(url) + "|" + defaultString(version);
					getProcessingMessages(npmPackage).add(msg);
					ourLog.info("Package[{}#{}] " + msg, packageId, packageVersionId);
				}
			}

			getProcessingMessages(npmPackage)
					.add("Successfully added package " + npmPackage.id() + "#" + npmPackage.version() + " to registry");

			return npmPackage;
		});
	}

	@Override
	public NpmPackage addPackageToCache(
			String thePackageId, String thePackageVersionId, InputStream thePackageTgzInputStream, String theSourceDesc)
			throws IOException {
		NpmPackageData npmData = myPackageLoaderSvc.createNpmPackageDataFromData(
				thePackageId, thePackageVersionId, theSourceDesc, thePackageTgzInputStream);

		return addPackageToCacheInternal(npmData);
	}

	private ResourceTable createResourceBinary(IBaseBinary theResourceBinary) {

		if (myPartitionSettings.isPartitioningEnabled()) {
			SystemRequestDetails requestDetails = new SystemRequestDetails();
			if (myPartitionSettings.isUnnamedPartitionMode() && myPartitionSettings.getDefaultPartitionId() != null) {
				requestDetails.setRequestPartitionId(
						RequestPartitionId.fromPartitionId(myPartitionSettings.getDefaultPartitionId()));
			} else {
				requestDetails.setTenantId(JpaConstants.DEFAULT_PARTITION_NAME);
			}
			return (ResourceTable)
					getBinaryDao().create(theResourceBinary, requestDetails).getEntity();
		} else {
			return (ResourceTable) getBinaryDao().create(theResourceBinary).getEntity();
		}
	}

	private boolean updateCurrentVersionFlagForAllPackagesBasedOnNewIncomingVersion(
			String thePackageId, String thePackageVersion) {
		Collection<NpmPackageVersionEntity> existingVersions = myPackageVersionDao.findByPackageId(thePackageId);
		boolean retVal = true;

		for (NpmPackageVersionEntity next : existingVersions) {
			int cmp = PackageVersionComparator.INSTANCE.compare(next.getVersionId(), thePackageVersion);
			assert cmp != 0;
			if (cmp < 0) {
				if (next.isCurrentVersion()) {
					next.setCurrentVersion(false);
					myPackageVersionDao.save(next);
				}
			} else {
				retVal = false;
			}
		}

		return retVal;
	}

	@Nonnull
	public FhirContext getFhirContext(FhirVersionEnum theFhirVersion) {
		return myVersionToContext.computeIfAbsent(theFhirVersion, v -> new FhirContext(v));
	}

	private IBaseBinary createPackageBinary(byte[] theBytes) {
		IBaseBinary binary = BinaryUtil.newBinary(myCtx);
		BinaryUtil.setData(myCtx, binary, theBytes, Constants.CT_APPLICATION_GZIP);
		return binary;
	}

	private IBaseBinary createPackageResourceBinary(String theFileName, byte[] theBytes, String theContentType) {
		IBaseBinary binary = BinaryUtil.newBinary(myCtx);
		BinaryUtil.setData(myCtx, binary, theBytes, theContentType);
		return binary;
	}

	private NpmPackageEntity createPackage(NpmPackage theNpmPackage) {
		NpmPackageEntity entity = new NpmPackageEntity();
		entity.setPackageId(theNpmPackage.id());
		entity.setCurrentVersionId(theNpmPackage.version());
		return myPackageDao.save(entity);
	}

	@Override
	@Transactional
	public NpmPackage loadPackage(String thePackageId, String thePackageVersion) throws FHIRException, IOException {
		// check package cache
		NpmPackage cachedPackage = loadPackageFromCacheOnly(thePackageId, thePackageVersion);
		if (cachedPackage != null) {
			return cachedPackage;
		}

		// otherwise we have to load it from packageloader
		NpmPackageData pkgData = myPackageLoaderSvc.fetchPackageFromPackageSpec(thePackageId, thePackageVersion);

		try {
			// and add it to the cache
			NpmPackage retVal = addPackageToCacheInternal(pkgData);
			getProcessingMessages(retVal)
					.add(
							0,
							"Package fetched from server at: "
									+ pkgData.getPackage().url());
			return retVal;
		} finally {
			pkgData.getInputStream().close();
		}
	}

	@Override
	public NpmPackage loadPackage(String theS) throws FHIRException, IOException {
		return loadPackage(theS, null);
	}

	private TransactionTemplate newTxTemplate() {
		return new TransactionTemplate(myTxManager);
	}

	@Override
	@Transactional(propagation = Propagation.NEVER)
	public NpmPackage installPackage(PackageInstallationSpec theInstallationSpec) throws IOException {
		Validate.notBlank(theInstallationSpec.getName(), "thePackageId must not be blank");
		Validate.notBlank(theInstallationSpec.getVersion(), "thePackageVersion must not be blank");

		String sourceDescription = "Embedded content";
		if (isNotBlank(theInstallationSpec.getPackageUrl())) {
			byte[] contents = myPackageLoaderSvc.loadPackageUrlContents(theInstallationSpec.getPackageUrl());
			theInstallationSpec.setPackageContents(contents);
			sourceDescription = theInstallationSpec.getPackageUrl();
		}

		if (theInstallationSpec.getPackageContents() != null) {
			return addPackageToCache(
					theInstallationSpec.getName(),
					theInstallationSpec.getVersion(),
					new ByteArrayInputStream(theInstallationSpec.getPackageContents()),
					sourceDescription);
		}

		return newTxTemplate().execute(tx -> {
			try {
				return loadPackage(theInstallationSpec.getName(), theInstallationSpec.getVersion());
			} catch (IOException e) {
				throw new InternalErrorException(Msg.code(1302) + e);
			}
		});
	}

	@Override
	@Transactional(readOnly = true)
	public IBaseResource loadPackageAssetByUrl(FhirVersionEnum theFhirVersion, String theCanonicalUrl) {

		String canonicalUrl = theCanonicalUrl;

		int versionSeparator = canonicalUrl.lastIndexOf('|');
		Slice<NpmPackageVersionResourceEntity> slice;
		if (versionSeparator != -1) {
			String canonicalVersion = canonicalUrl.substring(versionSeparator + 1);
			canonicalUrl = canonicalUrl.substring(0, versionSeparator);
			slice = myPackageVersionResourceDao.findCurrentVersionByCanonicalUrlAndVersion(
					PageRequest.of(0, 1), theFhirVersion, canonicalUrl, canonicalVersion);
		} else {
			slice = myPackageVersionResourceDao.findCurrentVersionByCanonicalUrl(
					PageRequest.of(0, 1), theFhirVersion, canonicalUrl);
		}

		if (slice.isEmpty()) {
			return null;
		} else {
			NpmPackageVersionResourceEntity contents = slice.getContent().get(0);
			return loadPackageEntity(contents);
		}
	}

	private IBaseResource loadPackageEntity(NpmPackageVersionResourceEntity contents) {
		try {
			JpaPid binaryPid = contents.getResourceBinary().getId();
			IBaseBinary binary = getBinaryDao().readByPid(binaryPid);
			byte[] resourceContentsBytes = fetchBlobFromBinary(binary);
			String resourceContents = new String(resourceContentsBytes, StandardCharsets.UTF_8);
			FhirContext packageContext = getFhirContext(contents.getFhirVersion());
			return EncodingEnum.detectEncoding(resourceContents)
					.newParser(packageContext)
					.parseResource(resourceContents);
		} catch (Exception e) {
			throw new RuntimeException(Msg.code(1305) + "Failed to load package resource " + contents, e);
		}
	}

	@Override
	@Transactional
	public NpmPackageMetadataJson loadPackageMetadata(String thePackageId) {
		NpmPackageMetadataJson retVal = new NpmPackageMetadataJson();

		Optional<NpmPackageEntity> pkg = myPackageDao.findByPackageId(thePackageId);
		if (!pkg.isPresent()) {
			throw new ResourceNotFoundException(Msg.code(1306) + "Unknown package ID: " + thePackageId);
		}

		List<NpmPackageVersionEntity> packageVersions =
				new ArrayList<>(myPackageVersionDao.findByPackageId(thePackageId));
		packageVersions.sort(new ReverseComparator<>(
				(o1, o2) -> PackageVersionComparator.INSTANCE.compare(o1.getVersionId(), o2.getVersionId())));

		for (NpmPackageVersionEntity next : packageVersions) {
			if (next.isCurrentVersion()) {
				retVal.setDistTags(new NpmPackageMetadataJson.DistTags().setLatest(next.getVersionId()));
			}

			NpmPackageMetadataJson.Version version = new NpmPackageMetadataJson.Version();
			version.setFhirVersion(next.getFhirVersionId());
			version.setAuthor(next.getAuthor());
			version.setDescription(next.getDescription());
			version.setName(next.getPackageId());
			version.setVersion(next.getVersionId());
			version.setBytes(next.getPackageSizeBytes());
			retVal.addVersion(version);
		}

		return retVal;
	}

	@Override
	@Transactional
	public PackageContents loadPackageContents(String thePackageId, String theVersion) {
		Optional<NpmPackageVersionEntity> entity = loadPackageVersionEntity(thePackageId, theVersion);
		return entity.map(t -> loadPackageContents(t)).orElse(null);
	}

	@Override
	@Transactional
	public NpmPackageSearchResultJson search(PackageSearchSpec thePackageSearchSpec) {
		NpmPackageSearchResultJson retVal = new NpmPackageSearchResultJson();

		CriteriaBuilder cb = myEntityManager.getCriteriaBuilder();

		// Query for total
		{
			CriteriaQuery<Long> countCriteriaQuery = cb.createQuery(Long.class);
			Root<NpmPackageVersionEntity> countCriteriaRoot = countCriteriaQuery.from(NpmPackageVersionEntity.class);
			countCriteriaQuery.multiselect(cb.countDistinct(countCriteriaRoot.get("myPackageId")));

			List<Predicate> predicates = createSearchPredicates(thePackageSearchSpec, cb, countCriteriaRoot);

			countCriteriaQuery.where(toPredicateArray(predicates));
			Long total = myEntityManager.createQuery(countCriteriaQuery).getSingleResult();
			retVal.setTotal(Math.toIntExact(total));
		}

		// Query for results
		{
			CriteriaQuery<NpmPackageVersionEntity> criteriaQuery = cb.createQuery(NpmPackageVersionEntity.class);
			Root<NpmPackageVersionEntity> root = criteriaQuery.from(NpmPackageVersionEntity.class);

			List<Predicate> predicates = createSearchPredicates(thePackageSearchSpec, cb, root);

			criteriaQuery.where(toPredicateArray(predicates));
			criteriaQuery.orderBy(cb.asc(root.get("myPackageId")));
			TypedQuery<NpmPackageVersionEntity> query = myEntityManager.createQuery(criteriaQuery);
			query.setFirstResult(thePackageSearchSpec.getStart());
			query.setMaxResults(thePackageSearchSpec.getSize());

			List<NpmPackageVersionEntity> resultList = query.getResultList();
			for (NpmPackageVersionEntity next : resultList) {

				if (!retVal.hasPackageWithId(next.getPackageId())) {
					retVal.addObject()
							.getPackage()
							.setName(next.getPackageId())
							.setAuthor(next.getAuthor())
							.setDescription(next.getDescription())
							.setVersion(next.getVersionId())
							.addFhirVersion(next.getFhirVersionId())
							.setBytes(next.getPackageSizeBytes());
				} else {
					NpmPackageSearchResultJson.Package retPackage = retVal.getPackageWithId(next.getPackageId());
					retPackage.addFhirVersion(next.getFhirVersionId());

					int cmp = PackageVersionComparator.INSTANCE.compare(next.getVersionId(), retPackage.getVersion());
					if (cmp > 0) {
						retPackage.setVersion(next.getVersionId());
					}
				}
			}
		}

		return retVal;
	}

	@Override
	@Transactional
	public PackageDeleteOutcomeJson uninstallPackage(String thePackageId, String theVersion) {
		PackageDeleteOutcomeJson retVal = new PackageDeleteOutcomeJson();

		Optional<NpmPackageVersionEntity> packageVersion =
				myPackageVersionDao.findByPackageIdAndVersion(thePackageId, theVersion);
		if (packageVersion.isPresent()) {

			String msg = "Deleting package " + thePackageId + "#" + theVersion;
			ourLog.info(msg);
			retVal.getMessage().add(msg);

			for (NpmPackageVersionResourceEntity next : packageVersion.get().getResources()) {
				msg = "Deleting package +" + thePackageId + "#" + theVersion + "resource: " + next.getCanonicalUrl();
				ourLog.info(msg);
				retVal.getMessage().add(msg);

				myPackageVersionResourceDao.delete(next);

				ExpungeOptions options = new ExpungeOptions();
				options.setExpungeDeletedResources(true).setExpungeOldVersions(true);
				deleteAndExpungeResourceBinary(
						next.getResourceBinary().getIdDt().toVersionless(), options);
			}

			myPackageVersionDao.delete(packageVersion.get());

			ExpungeOptions options = new ExpungeOptions();
			options.setExpungeDeletedResources(true).setExpungeOldVersions(true);
			deleteAndExpungeResourceBinary(
					packageVersion.get().getPackageBinary().getIdDt().toVersionless(), options);

			Collection<NpmPackageVersionEntity> remainingVersions = myPackageVersionDao.findByPackageId(thePackageId);
			if (remainingVersions.size() == 0) {
				msg = "No versions of package " + thePackageId + " remain";
				ourLog.info(msg);
				retVal.getMessage().add(msg);
				Optional<NpmPackageEntity> pkgEntity = myPackageDao.findByPackageId(thePackageId);
				myPackageDao.delete(pkgEntity.get());
			} else {

				List<NpmPackageVersionEntity> versions = remainingVersions.stream()
						.sorted((o1, o2) ->
								PackageVersionComparator.INSTANCE.compare(o1.getVersionId(), o2.getVersionId()))
						.collect(Collectors.toList());
				for (int i = 0; i < versions.size(); i++) {
					boolean isCurrent = i == versions.size() - 1;
					if (isCurrent != versions.get(i).isCurrentVersion()) {
						versions.get(i).setCurrentVersion(isCurrent);
						myPackageVersionDao.save(versions.get(i));
					}
				}
			}

		} else {

			String msg = "No package found with the given ID";
			retVal.getMessage().add(msg);
		}

		return retVal;
	}

	@Override
	@Transactional
	public List<IBaseResource> loadPackageAssetsByType(FhirVersionEnum theFhirVersion, String theResourceType) {
		//		List<NpmPackageVersionResourceEntity> outcome = myPackageVersionResourceDao.findAll();
		Slice<NpmPackageVersionResourceEntity> outcome = myPackageVersionResourceDao.findCurrentVersionByResourceType(
				PageRequest.of(0, 1000), theFhirVersion, theResourceType);
		return outcome.stream().map(t -> loadPackageEntity(t)).collect(Collectors.toList());
	}

	private void deleteAndExpungeResourceBinary(IIdType theResourceBinaryId, ExpungeOptions theOptions) {
		getBinaryDao().delete(theResourceBinaryId, new SystemRequestDetails()).getEntity();
		getBinaryDao().forceExpungeInExistingTransaction(theResourceBinaryId, theOptions, new SystemRequestDetails());
	}

	@Nonnull
	public List<Predicate> createSearchPredicates(
			PackageSearchSpec thePackageSearchSpec, CriteriaBuilder theCb, Root<NpmPackageVersionEntity> theRoot) {
		List<Predicate> predicates = new ArrayList<>();

		if (isNotBlank(thePackageSearchSpec.getResourceUrl())) {
			Join<NpmPackageVersionEntity, NpmPackageVersionResourceEntity> resources =
					theRoot.join("myResources", JoinType.LEFT);

			predicates.add(theCb.equal(resources.get("myCanonicalUrl"), thePackageSearchSpec.getResourceUrl()));
		}

		if (isNotBlank(thePackageSearchSpec.getVersion())) {
			String searchTerm = thePackageSearchSpec.getVersion() + "%";
			predicates.add(theCb.like(theRoot.get("myVersionId"), searchTerm));
		}

		if (isNotBlank(thePackageSearchSpec.getDescription())) {
			String searchTerm = "%" + thePackageSearchSpec.getDescription() + "%";
			searchTerm = StringUtil.normalizeStringForSearchIndexing(searchTerm);
			predicates.add(theCb.like(theCb.upper(theRoot.get("myDescriptionUpper")), searchTerm));
		}

		if (isNotBlank(thePackageSearchSpec.getAuthor())) {
			String searchTerm = "%" + thePackageSearchSpec.getAuthor() + "%";
			searchTerm = StringUtil.normalizeStringForSearchIndexing(searchTerm);
			predicates.add(theCb.like(theRoot.get("myAuthorUpper"), searchTerm));
		}

		if (isNotBlank(thePackageSearchSpec.getFhirVersion())) {
			if (!thePackageSearchSpec.getFhirVersion().matches("([0-9]+\\.)+[0-9]+")) {
				FhirVersionEnum versionEnum = FhirVersionEnum.forVersionString(thePackageSearchSpec.getFhirVersion());
				if (versionEnum != null) {
					predicates.add(theCb.equal(theRoot.get("myFhirVersion").as(String.class), versionEnum.name()));
				}
			} else {
				predicates.add(theCb.like(theRoot.get("myFhirVersionId"), thePackageSearchSpec.getFhirVersion() + "%"));
			}
		}

		return predicates;
	}

	@SuppressWarnings("unchecked")
	public static List<String> getProcessingMessages(NpmPackage thePackage) {
		return (List<String>)
				thePackage.getUserData().computeIfAbsent("JpPackageCache_ProcessingMessages", t -> new ArrayList<>());
	}

	/**
	 * Truncates a string to {@link NpmPackageVersionEntity#PACKAGE_DESC_LENGTH} which is
	 * the maximum length used on several columns in {@link NpmPackageVersionEntity}. If the
	 * string is longer than the maximum allowed, the last 3 characters are replaced with "..."
	 */
	private static String truncateStorageString(String theInput) {
		String retVal = null;
		if (theInput != null) {
			if (theInput.length() > NpmPackageVersionEntity.PACKAGE_DESC_LENGTH) {
				retVal = theInput.substring(0, NpmPackageVersionEntity.PACKAGE_DESC_LENGTH - 4) + "...";
			} else {
				retVal = theInput;
			}
		}
		return retVal;
	}
}
