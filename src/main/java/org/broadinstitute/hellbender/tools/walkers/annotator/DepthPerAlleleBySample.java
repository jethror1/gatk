package org.broadinstitute.hellbender.tools.walkers.annotator;

import htsjdk.variant.variantcontext.Allele;
import htsjdk.variant.variantcontext.Genotype;
import htsjdk.variant.variantcontext.GenotypeBuilder;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.vcf.VCFConstants;
import htsjdk.variant.vcf.VCFFormatHeaderLine;
import htsjdk.variant.vcf.VCFStandardHeaderLines;
import org.broadinstitute.barclay.help.DocumentedFeature;
import org.broadinstitute.hellbender.engine.ReferenceContext;
import org.broadinstitute.hellbender.utils.Utils;
import org.broadinstitute.hellbender.utils.genotyper.AlleleLikelihoods;
import org.broadinstitute.hellbender.utils.help.HelpConstants;
import org.broadinstitute.hellbender.utils.read.GATKRead;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Depth of coverage of each allele per sample
 *
 * <p>Also known as the allele depth, this annotation gives the unfiltered count of reads that support a given allele for an individual sample. The values in the field are ordered to match the order of alleles specified in the REF and ALT fields: REF, ALT1, ALT2 and so on if there are multiple ALT alleles.</p>
 *
 * <p>See the method documentation on <a href="http://www.broadinstitute.org/gatk/guide/article?id=4721">using coverage information</a> for important interpretation details.</p>
 *
 * <h3>Caveats</h3>
 * <ul>
 *     <li>The AD calculation as performed by HaplotypeCaller may not yield exact results because only reads that statistically favor one allele over the other are counted. Due to this fact, the sum of AD may be different than the individual sample depth, especially when there are many non-informative reads.</li>
 *     <li>Because the AD includes reads and bases that were filtered by the caller (and in case of indels, is based on a statistical computation), it  should not be used to make assumptions about the genotype that it is associated with. Ultimately, the phred-scaled genotype likelihoods (PLs) are what determines the genotype calls.</li>
 * </ul>
 *
 * <h3>Related annotations</h3>
 * <ul>
 *     <li><b><a href="https://www.broadinstitute.org/gatk/guide/tooldocs/org_broadinstitute_gatk_tools_walkers_annotator_Coverage.php">Coverage</a></b> gives the filtered depth of coverage for each sample and the unfiltered depth across all samples.</li>
 * </ul>
 */
@DocumentedFeature(groupName=HelpConstants.DOC_CAT_ANNOTATORS, groupSummary=HelpConstants.DOC_CAT_ANNOTATORS_SUMMARY, summary="Depth of coverage of each allele per sample (AD)")
public final class DepthPerAlleleBySample implements GenotypeAnnotation, StandardAnnotation, StandardMutectAnnotation {

    @Override
    public void annotate(final ReferenceContext ref,
                         final VariantContext vc,
                         final Genotype g,
                         final GenotypeBuilder gb,
                         final AlleleLikelihoods<GATKRead, Allele> likelihoods) {
        Utils.nonNull(gb, "gb is null");
        Utils.nonNull(vc, "vc is null");

        if ( g == null || !g.isCalled() || likelihoods == null) {
            return;
        }
        final Set<Allele> alleles = new LinkedHashSet<>(vc.getAlleles());

        // make sure that there's a meaningful relationship between the alleles in the likelihoods and our VariantContext
        Utils.validateArg(likelihoods.alleles().containsAll(alleles), () -> "VC alleles " + alleles + " not a  subset of AlleleLikelihoods alleles " + likelihoods.alleles());

        gb.AD(annotateWithLikelihoods(vc, g, alleles, likelihoods));
    }

    protected int[] annotateWithLikelihoods(VariantContext vc, Genotype g, Set<Allele> alleles, final AlleleLikelihoods<GATKRead, Allele> likelihoods) {

        final Map<Allele, Integer> alleleCounts = new LinkedHashMap<>();
        for ( final Allele allele : vc.getAlleles() ) {
            alleleCounts.put(allele, 0);
        }
        final Map<Allele, List<Allele>> alleleSubset = alleles.stream().collect(Collectors.toMap(a -> a, Arrays::asList));
        final AlleleLikelihoods<GATKRead, Allele> subsettedLikelihoods = likelihoods.marginalize(alleleSubset);
        subsettedLikelihoods.bestAllelesBreakingTies(g.getSampleName()).stream()
                .filter(ba -> ba.isInformative())
                .forEach(ba -> alleleCounts.compute(ba.allele, (allele,prevCount) -> prevCount + 1));

        final int[] counts = new int[alleleCounts.size()];
        counts[0] = alleleCounts.get(vc.getReference()); //first one in AD is always ref
        for (int i = 0; i < vc.getNAlleles() -1; i++) {
            counts[i + 1] = alleleCounts.get(vc.getAlternateAllele(i));
        }

        return counts;
    }

    @Override
    public List<String> getKeyNames() { return Collections.singletonList(VCFConstants.GENOTYPE_ALLELE_DEPTHS); }
}
