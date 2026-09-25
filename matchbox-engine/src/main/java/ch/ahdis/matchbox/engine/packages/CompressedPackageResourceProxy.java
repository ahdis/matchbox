package ch.ahdis.matchbox.engine.packages;

import java.io.ByteArrayOutputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import org.hl7.fhir.exceptions.FHIRException;
import org.hl7.fhir.r5.context.CanonicalResourceManager.CanonicalResourceProxy;
import org.hl7.fhir.r5.model.CanonicalResource;
import org.hl7.fhir.r5.model.PackageInformation;
import org.hl7.fhir.r5.model.Resource;
import org.hl7.fhir.utilities.npm.NpmPackage.PackageResourceInformation;

/**
 * A conformance resource of a package that is registered in the worker context with the metadata of the package
 * index only, and parsed when it's first used (lazy loading, like core's SimpleWorkerContext.PackageResourceLoader for
 * packages on the filesystem).
 * <p>
 * The packages of the JPA package cache are read into memory, so the proxy keeps the file content itself. It's
 * compressed, because the raw JSON is almost as big as the parsed resource. Once the resource is loaded, the context
 * drops the proxy and with it the compressed content.
 */
public class CompressedPackageResourceProxy extends CanonicalResourceProxy {

	/**
	 * Parses the file content of a resource into an R5 resource.
	 */
	@FunctionalInterface
	public interface ResourceParser {
		Resource parse(byte[] content, String filename) throws Exception;
	}

	private final byte[] compressed;
	private final int length;
	private final String filename;
	private final ResourceParser parser;
	private final PackageInformation packageInformation;

	/**
	 * @param pri      the index information of the resource
	 * @param url      the canonical URL of the resource (the loader may patch the one of the index)
	 * @param filename the filename in the package
	 * @param content  the file content
	 */
	public CompressedPackageResourceProxy(final PackageResourceInformation pri,
													  final String url,
													  final String filename,
													  final byte[] content,
													  final ResourceParser parser,
													  final PackageInformation packageInformation) {
		super(pri.getResourceType(), pri.getId(), url, pri.getVersion(), pri.getSupplements(),
				pri.getDerivation(), pri.getContent());
		this.compressed = compress(content);
		this.length = content.length;
		this.filename = filename;
		this.parser = parser;
		this.packageInformation = packageInformation;
	}

	@Override
	public CanonicalResource loadResource() throws FHIRException {
		try {
			final Resource resource = this.parser.parse(decompress(this.compressed, this.length), this.filename);
			if (!(resource instanceof final CanonicalResource canonicalResource)) {
				throw new FHIRException("Resource " + this.filename + " of package " + this.packageInformation.getVID()
													+ " is not a canonical resource");
			}
			canonicalResource.setSourcePackage(this.packageInformation);
			return canonicalResource;
		} catch (final FHIRException e) {
			throw e;
		} catch (final Exception e) {
			throw new FHIRException("Error loading " + this.filename + " of package " + this.packageInformation.getVID()
												+ ": " + e.getMessage(), e);
		}
	}

	static byte[] compress(final byte[] content) {
		final Deflater deflater = new Deflater(Deflater.BEST_SPEED);
		try {
			deflater.setInput(content);
			deflater.finish();
			final ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, content.length / 4));
			final byte[] buffer = new byte[8192];
			while (!deflater.finished()) {
				out.write(buffer, 0, deflater.deflate(buffer));
			}
			return out.toByteArray();
		} finally {
			deflater.end();
		}
	}

	static byte[] decompress(final byte[] compressed, final int length) throws DataFormatException {
		final Inflater inflater = new Inflater();
		try {
			inflater.setInput(compressed);
			final byte[] content = new byte[length];
			int offset = 0;
			while (offset < length && !inflater.finished()) {
				final int inflated = inflater.inflate(content, offset, length - offset);
				if (inflated == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
					throw new DataFormatException("Truncated compressed content");
				}
				offset += inflated;
			}
			return content;
		} finally {
			inflater.end();
		}
	}
}
