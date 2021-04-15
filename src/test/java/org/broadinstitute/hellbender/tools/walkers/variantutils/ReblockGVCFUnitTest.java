package org.broadinstitute.hellbender.tools.walkers.variantutils;

import htsjdk.samtools.SAMSequenceDictionary;
import htsjdk.samtools.reference.ReferenceSequenceFile;
import htsjdk.variant.utils.SAMSequenceDictionaryExtractor;
import htsjdk.variant.variantcontext.*;
import htsjdk.variant.variantcontext.writer.VariantContextWriter;
import htsjdk.variant.variantcontext.writer.VariantContextWriterBuilder;
import htsjdk.variant.vcf.*;
import org.apache.commons.lang3.tuple.Pair;
import org.broadinstitute.hellbender.CommandLineProgramTest;
import org.broadinstitute.hellbender.GATKBaseTest;
import org.broadinstitute.hellbender.engine.GATKPath;
import org.broadinstitute.hellbender.testutils.ArgumentsBuilder;
import org.broadinstitute.hellbender.testutils.VariantContextTestUtils;
import org.broadinstitute.hellbender.tools.walkers.annotator.Coverage;
import org.broadinstitute.hellbender.tools.walkers.annotator.RMSMappingQuality;
import org.broadinstitute.hellbender.tools.walkers.annotator.VariantAnnotatorEngine;
import org.broadinstitute.hellbender.tools.walkers.annotator.allelespecific.AS_FisherStrand;
import org.broadinstitute.hellbender.tools.walkers.annotator.allelespecific.AS_RMSMappingQuality;
import org.broadinstitute.hellbender.tools.walkers.annotator.allelespecific.AS_ReadPosRankSumTest;
import org.broadinstitute.hellbender.utils.reference.ReferenceUtils;
import htsjdk.variant.vcf.VCFConstants;
import org.broadinstitute.hellbender.utils.variant.GATKVCFConstants;
import org.broadinstitute.hellbender.utils.variant.writers.GVCFWriter;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class ReblockGVCFUnitTest extends CommandLineProgramTest {
    private final static Allele LONG_REF = Allele.create("ACTG", true);
    private final static Allele DELETION = Allele.create("A", false);
    private final static Allele SHORT_REF = Allele.create("A", true);
    private final static Allele LONG_SNP = Allele.create("TCTA", false);
    private final static Allele SHORT_SNP = Allele.create("T", false);
    private final static int EXAMPLE_DP = 18;

    @Test
    public void testCleanUpHighQualityVariant() {
        final ReblockGVCF reblocker = new ReblockGVCF();
        //We need an annotation engine for cleanUpHighQualityVariant()
        reblocker.createAnnotationEngine();
        reblocker.dropLowQuals = true;
        reblocker.doQualApprox = true;

        final Genotype g0 = VariantContextTestUtils.makeG("sample1", LONG_REF, DELETION, 41, 0, 37, 200, 100, 200, 400, 600, 800, 1200);
        final Genotype g = addAD(g0,13,17,0,0);
        final VariantContext extraAlt0 = makeDeletionVC("lowQualVar", Arrays.asList(LONG_REF, DELETION, LONG_SNP, Allele.NON_REF_ALLELE), LONG_REF.length(), g);
        final Map<String, Object> attr = new HashMap<>();
        attr.put(VCFConstants.DEPTH_KEY, 32);
        final VariantContext extraAlt = addAttributes(extraAlt0, attr);
        //we'll call this with the same VC again under the assumption that STAND_CALL_CONF is zero so no alleles/GTs change
        final VariantContext cleaned1 = reblocker.cleanUpHighQualityVariant(extraAlt);
        Assert.assertTrue(cleaned1.getAlleles().size() == 3);
        Assert.assertTrue(cleaned1.getAlleles().contains(LONG_REF));
        Assert.assertTrue(cleaned1.getAlleles().contains(DELETION));
        Assert.assertTrue(cleaned1.getAlleles().contains(Allele.NON_REF_ALLELE));
        Assert.assertTrue(cleaned1.hasAttribute(GATKVCFConstants.RAW_QUAL_APPROX_KEY));
        Assert.assertTrue(cleaned1.getAttribute(GATKVCFConstants.RAW_QUAL_APPROX_KEY).equals(41));
        Assert.assertTrue(cleaned1.hasAttribute(GATKVCFConstants.VARIANT_DEPTH_KEY));
        Assert.assertTrue(cleaned1.getAttribute(GATKVCFConstants.VARIANT_DEPTH_KEY).equals(30));
        Assert.assertTrue(cleaned1.hasAttribute(GATKVCFConstants.RAW_MAPPING_QUALITY_WITH_DEPTH_KEY));
        Assert.assertTrue(cleaned1.getAttributeAsString(GATKVCFConstants.RAW_MAPPING_QUALITY_WITH_DEPTH_KEY,"").split(",")[1].equals("32"));

        final Genotype hetNonRef = VariantContextTestUtils.makeG("sample2", DELETION, LONG_SNP, 891,879,1128,84,0,30,891,879,84,891);
        final VariantContext keepAlts = makeDeletionVC("keepAllAlts", Arrays.asList(LONG_REF, DELETION, LONG_SNP, Allele.NON_REF_ALLELE), LONG_REF.length(), hetNonRef);
        Assert.assertTrue(keepAlts.getAlleles().size() == 4);
        Assert.assertTrue(keepAlts.getAlleles().contains(LONG_REF));
        Assert.assertTrue(keepAlts.getAlleles().contains(DELETION));
        Assert.assertTrue(keepAlts.getAlleles().contains(LONG_SNP));
        Assert.assertTrue(keepAlts.getAlleles().contains(Allele.NON_REF_ALLELE));
    }

    @Test
    public void testLowQualVariantToGQ0HomRef() {
        final ReblockGVCF reblocker = new ReblockGVCF();

        reblocker.dropLowQuals = true;
        final Genotype g = VariantContextTestUtils.makeG("sample1", LONG_REF, Allele.NON_REF_ALLELE, 200, 100, 200, 11, 0, 37);
        final VariantContext toBeNoCalled = makeDeletionVC("lowQualVar", Arrays.asList(LONG_REF, DELETION, Allele.NON_REF_ALLELE), LONG_REF.length(), g);
        final VariantContextBuilder dropped = reblocker.lowQualVariantToGQ0HomRef(toBeNoCalled);
        Assert.assertEquals(dropped, null);

        reblocker.dropLowQuals = false;
        final VariantContext modified = reblocker.lowQualVariantToGQ0HomRef(toBeNoCalled).make();
        Assert.assertTrue(modified.getAttributes().containsKey(VCFConstants.END_KEY));
        Assert.assertTrue(modified.getAttributes().get(VCFConstants.END_KEY).equals(13));
        Assert.assertTrue(modified.getReference().equals(SHORT_REF));
        Assert.assertTrue(modified.getAlternateAllele(0).equals(Allele.NON_REF_ALLELE));
        Assert.assertTrue(!modified.filtersWereApplied());
        Assert.assertTrue(modified.getLog10PError() == VariantContext.NO_LOG10_PERROR);

        //No-calls were throwing NPEs.  Now they're not.
        final Genotype g2 = VariantContextTestUtils.makeG("sample1", Allele.NO_CALL,Allele.NO_CALL);
        final VariantContext noData = makeDeletionVC("noData", Arrays.asList(LONG_REF, DELETION, Allele.NON_REF_ALLELE), LONG_REF.length(), g2);
        final VariantContext notCrashing = reblocker.lowQualVariantToGQ0HomRef(noData).make();
        final Genotype outGenotype = notCrashing.getGenotype(0);
        Assert.assertTrue(outGenotype.isHomRef());
        Assert.assertEquals(outGenotype.getGQ(), 0);
        Assert.assertTrue(Arrays.stream(outGenotype.getPL()).allMatch(x -> x == 0));

        //haploid hom ref call
        final int[] pls = {0, 35, 72};
        final GenotypeBuilder gb = new GenotypeBuilder("male_sample", Arrays.asList(LONG_REF)).PL(pls);
        final VariantContextBuilder vb = new VariantContextBuilder();
        vb.chr("20").start(10001).stop(10004).alleles(Arrays.asList(LONG_REF, DELETION, Allele.NON_REF_ALLELE)).log10PError(-3.0).genotypes(gb.make());
        final VariantContext vc = vb.make();

        final VariantContext haploidRefBlock = reblocker.lowQualVariantToGQ0HomRef(vc).make();
        final Genotype newG = haploidRefBlock.getGenotype("male_sample");

        Assert.assertEquals(newG.getPloidy(), 1);
        Assert.assertEquals(newG.getGQ(), 35);
    }

    @Test
    public void testCalledHomRefGetsAltGQ() {
        final ReblockGVCF reblocker = new ReblockGVCF();

        final Genotype g3 = VariantContextTestUtils.makeG("sample1", LONG_REF, LONG_REF, 0, 11, 37, 100, 200, 400);
        final VariantContext twoAltsHomRef = makeDeletionVC("lowQualVar", Arrays.asList(LONG_REF, DELETION, Allele.NON_REF_ALLELE), LONG_REF.length(), g3);
        final GenotypeBuilder takeGoodAltGQ = reblocker.changeCallToHomRefVersusNonRef(twoAltsHomRef, new HashMap<>());
        final Genotype nowRefBlock = takeGoodAltGQ.make();
        Assert.assertEquals(nowRefBlock.getGQ(), 11);
        Assert.assertEquals(nowRefBlock.getDP(), 18);
        Assert.assertEquals((int)nowRefBlock.getExtendedAttribute(GATKVCFConstants.MIN_DP_FORMAT_KEY), 18);
    }

    @Test
    public void testChangeCallToGQ0HomRef() {
        final ReblockGVCF reblocker = new ReblockGVCF();

        final Genotype g = VariantContextTestUtils.makeG("sample1", LONG_REF, Allele.NON_REF_ALLELE, 200, 100, 200, 11, 0, 37);
        final VariantContext toBeNoCalled = makeDeletionVC("lowQualVar", Arrays.asList(LONG_REF, DELETION, Allele.NON_REF_ALLELE), LONG_REF.length(), g);
        final Map<String, Object> noAttributesMap = new HashMap<>();
        final GenotypeBuilder noCalled = reblocker.changeCallToHomRefVersusNonRef(toBeNoCalled, noAttributesMap);
        final Genotype newG = noCalled.make();
        Assert.assertTrue(noAttributesMap.containsKey(VCFConstants.END_KEY));
        Assert.assertTrue(noAttributesMap.get(VCFConstants.END_KEY).equals(13));
        Assert.assertTrue(newG.getAllele(0).equals(SHORT_REF));
        Assert.assertTrue(newG.getAllele(1).equals(SHORT_REF));
        Assert.assertTrue(!newG.hasAD());
    }

    @Test  //no-calls can be dropped or reblocked just like hom-refs, i.e. we don't have to preserve them like variants
    public void testBadCalls() {
        final ReblockGVCF reblocker = new ReblockGVCF();

        final Genotype g2 = VariantContextTestUtils.makeG("sample1", Allele.NO_CALL,Allele.NO_CALL);
        final VariantContext noData = makeDeletionVC("noData", Arrays.asList(LONG_REF, DELETION, Allele.NON_REF_ALLELE), LONG_REF.length(), g2);
        Assert.assertTrue(reblocker.shouldBeReblocked(noData));

        final Genotype g3 = VariantContextTestUtils.makeG("sample1", LONG_REF, Allele.NON_REF_ALLELE);
        final VariantContext nonRefCall = makeDeletionVC("nonRefCall", Arrays.asList(LONG_REF, DELETION, Allele.NON_REF_ALLELE), LONG_REF.length(), g3);
        Assert.assertTrue(reblocker.shouldBeReblocked(nonRefCall));
    }

    @Test
    public void testPosteriors() {
        final ReblockGVCF reblocker = new ReblockGVCF();
        reblocker.posteriorsKey = "GP";

        final GenotypeBuilder gb = new GenotypeBuilder("sample1", Arrays.asList(LONG_REF, LONG_REF));
        final double[] posteriors = {0,2,5.01,2,4,5.01,2,4,4,5.01};
        final int[] pls = {2,0,50,2,289,52,38,325,407,88};
        gb.attribute("GP", posteriors).PL(pls).GQ(-2); //I kid you not, DRAGEN output a -2 GQ
        final VariantContext vc = makeDeletionVC("DRAGEN", Arrays.asList(LONG_REF, DELETION, LONG_SNP, Allele.NON_REF_ALLELE), LONG_REF.length(), gb.make());
//        Assert.assertTrue(reblocker.shouldBeReblocked(vc));
//        final VariantContext out = reblocker.lowQualVariantToGQ0HomRef(vc, vc).make();
//        Assert.assertTrue(out.getGenotype(0).isHomRef());
//        Assert.assertTrue(out.getGenotype(0).getGQ() == 2);

        final GenotypeBuilder gb2 = new GenotypeBuilder("sample1", Arrays.asList(LONG_REF, LONG_REF));
        final double gqForNoPLs = 34.77;
        final int inputGQ = 32;
        Assert.assertNotEquals(gqForNoPLs, inputGQ);
        final double[] posteriors2 = {0,gqForNoPLs,37.78,39.03,73.8,42.04};
        gb2.attribute("GP", posteriors2).GQ(inputGQ);
        final VariantContext vc2 = makeDeletionVC("DRAGEN", Arrays.asList(LONG_REF, DELETION, Allele.NON_REF_ALLELE), LONG_REF.length(), gb2.make());
        final VariantContext out2 = reblocker.lowQualVariantToGQ0HomRef(vc2).make();
        final Genotype gOut2 = out2.getGenotype(0);
        Assert.assertTrue(gOut2.isHomRef());
        Assert.assertEquals(gOut2.getGQ(), (int)Math.round(gqForNoPLs));
        Assert.assertTrue(gOut2.hasPL());
        Assert.assertEquals(gOut2.getPL().length, 3);
    }

    @DataProvider(name = "overlappingDeletionCases")
    public Object[][] createOverlappingDeletionCases() {
        return new Object[][] {
                {100000, 10, 100005, 10, 99, 99, 2},
                {100000, 10, 100005, 10, 99, 5, 2},
                {100000, 10, 100005, 10, 5, 99, 2},
                {100000, 10, 100005, 10, 5, 5, 1},
                {100000, 15, 100010, 5, 99, 99, 2},
                {100000, 15, 100010, 5, 99, 5, 1},
                {100000, 15, 100005, 5, 5, 99, 3},
                {100000, 15, 100005, 5, 5, 5, 1}
        };
    }

    @Test(dataProvider = "overlappingDeletionCases")
    public void testOverlappingDeletions(final int del1start, final int del1length,
                                         final int del2start, final int del2length,
                                         final int del1qual, final int del2qual, final int numExpected) throws IOException {
        final String inputPrefix = "overlappingDeletions";
        final String inputSuffix = ".g.vcf";
        final File inputFile = File.createTempFile(inputPrefix, inputSuffix);
        final GVCFWriter gvcfWriter= setUpWriter(inputFile, new File(GATKBaseTest.FULL_HG19_DICT));

        final ReferenceSequenceFile ref = ReferenceUtils.createReferenceReader(new GATKPath(GATKBaseTest.b37Reference));
        final Allele del1Ref = Allele.create(ReferenceUtils.getRefBasesAtPosition(ref, "20", del1start, del1length), true);
        final Allele del1Alt = Allele.create(ReferenceUtils.getRefBaseAtPosition(ref, "20", del1start), false);
        final Allele del2Ref = Allele.create(ReferenceUtils.getRefBasesAtPosition(ref, "20", del2start, del2length), true);
        final Allele del2Alt = Allele.create(ReferenceUtils.getRefBaseAtPosition(ref, "20", del2start), false);
        final VariantContextBuilder variantContextBuilder = new VariantContextBuilder();
        variantContextBuilder.chr("20").start(del1start).stop(del1start+del1length-1).attribute(VCFConstants.DEPTH_KEY, 10).alleles(Arrays.asList(del1Ref, del1Alt, Allele.NON_REF_ALLELE));
        final VariantContext del1 = VariantContextTestUtils.makeGVCFVariantContext(variantContextBuilder, Arrays.asList(del1Ref, del1Alt), del1qual);

        variantContextBuilder.chr("20").start(del2start).stop(del2start+del2length-1).attribute(VCFConstants.DEPTH_KEY, 10).alleles(Arrays.asList(del2Ref, del2Alt, Allele.NON_REF_ALLELE));
        final VariantContext del2 = VariantContextTestUtils.makeGVCFVariantContext(variantContextBuilder, Arrays.asList(del2Ref, del2Alt), del2qual);

        gvcfWriter.add(del1);
        gvcfWriter.add(del2);
        gvcfWriter.close();

        final File outputFile = File.createTempFile(inputPrefix,".reblocked" + inputSuffix);
        final ArgumentsBuilder args = new ArgumentsBuilder();
        args.add("V", inputFile)
            .add(ReblockGVCF.RGQ_THRESHOLD_SHORT_NAME, 10.0)
            .addReference(b37_reference_20_21)
            .addOutput(outputFile);
        runCommandLine(args);

        final Pair<VCFHeader, List<VariantContext>> outVCs = VariantContextTestUtils.readEntireVCFIntoMemory(outputFile.getAbsolutePath());
        Assert.assertEquals(outVCs.getRight().size(), numExpected);
    }

    //TODO: test for uncalled alts, which we want to always drop
    @Test
    public void testIndelTrimming() throws IOException {
        final String inputPrefix = "altToTrim";
        final String inputSuffix = ".g.vcf";
        final File inputFile = new File(inputPrefix+inputSuffix);
        final GVCFWriter gvcfWriter= setUpWriter(inputFile, new File(GATKBaseTest.FULL_HG19_DICT));

        final int longestDelLength = 20;
        final int del1start = 200000;
        final int goodDelLength = 10;
        final ReferenceSequenceFile ref = ReferenceUtils.createReferenceReader(new GATKPath(GATKBaseTest.b37Reference));
        final Allele del1Ref = Allele.create(ReferenceUtils.getRefBasesAtPosition(ref, "20", del1start, longestDelLength), true);  //+1 for anchor base
        final Allele del1Alt2 = Allele.create(ReferenceUtils.getRefBaseAtPosition(ref, "20", del1start), false);
        final Allele del1Alt1 = Allele.extend(Allele.create(del1Alt2, true), ReferenceUtils.getRefBasesAtPosition(ref, "20", del1start+goodDelLength, goodDelLength));
        final VariantContextBuilder variantContextBuilder = new VariantContextBuilder();
        variantContextBuilder.chr("20").start(del1start).stop(del1start+longestDelLength-1).attribute(VCFConstants.DEPTH_KEY, 10).alleles(Arrays.asList(del1Ref, del1Alt1, del1Alt2, Allele.NON_REF_ALLELE));
        final GenotypeBuilder gb = new GenotypeBuilder(VariantContextTestUtils.SAMPLE_NAME, Arrays.asList(del1Ref, del1Alt1));
        gb.PL(new int[]{50, 0, 100, 150, 200, 300, 400, 500, 600, 1000});
        variantContextBuilder.genotypes(gb.make());
        final VariantContext del1 = variantContextBuilder.make();

        final int goodStart = del1start + longestDelLength;
        final Allele goodRef = Allele.create(ReferenceUtils.getRefBasesAtPosition(ref, "20", goodStart, 1), true);
        final Allele goodSNP = VariantContextTestUtils.makeAnySNPAlt(goodRef);  //generate valid data, but make it agnostic to position and reference genome
        variantContextBuilder.start(goodStart).stop(goodStart).alleles(Arrays.asList(goodRef, goodSNP, Allele.NON_REF_ALLELE));
        final GenotypeBuilder gb2 = new GenotypeBuilder(VariantContextTestUtils.SAMPLE_NAME, Arrays.asList(goodRef, goodSNP));
        gb2.PL(new int[]{50, 0, 100, 150, 200, 300});
        gb2.GQ(50);
        variantContextBuilder.genotypes(gb2.make());
        final VariantContext keepVar = variantContextBuilder.make();

        gvcfWriter.add(del1);
        gvcfWriter.add(keepVar);
        gvcfWriter.close();

        final File outputFile = File.createTempFile(inputPrefix,".reblocked" + inputSuffix);
        final ArgumentsBuilder args = new ArgumentsBuilder();
        args.add("V", inputFile)
                .addReference(b37_reference_20_21)
                .addOutput(outputFile);
        runCommandLine(args);

        final List<VariantContext> outVCs = VariantContextTestUtils.readEntireVCFIntoMemory(outputFile.getAbsolutePath()).getRight();
        Assert.assertEquals(outVCs.size(), 3);
        Assert.assertTrue(outVCs.get(0).isVariant());
        Assert.assertTrue(outVCs.get(1).isReferenceBlock());
        Assert.assertEquals(outVCs.get(1).getStart(), del1start + goodDelLength);
        Assert.assertTrue(outVCs.get(2).isVariant());
    }

    @Test
    public void testAnnotationSubsetting() {
        final VariantAnnotatorEngine annotationEngine = new VariantAnnotatorEngine(Arrays.asList(new Coverage(), new AS_RMSMappingQuality(),
                new RMSMappingQuality(), new AS_ReadPosRankSumTest(), new AS_FisherStrand()), null, Collections.emptyList(), false, false);

        final Genotype g0 = VariantContextTestUtils.makeG("sample1", LONG_REF, LONG_SNP, 41, 0, 37, 200, 100, 200, 400, 600, 800, 1200, 5000, 5000, 5000, 5000, 5000);
        final Genotype g = addAD(g0,13,17,0,0,1);

        final VariantContext originalVCbase = makeDeletionVC("", Arrays.asList(LONG_REF, LONG_SNP, DELETION, Allele.NON_REF_ALLELE), LONG_REF.length(), g);
        final VariantContextBuilder originalBuilder = new VariantContextBuilder(originalVCbase);
        final Map<String, Object> origAttributes = new LinkedHashMap<>();
        origAttributes.put(VCFConstants.DEPTH_KEY, 93);
        origAttributes.put(GATKVCFConstants.RAW_RMS_MAPPING_QUALITY_DEPRECATED, "329810.0");
        origAttributes.put(GATKVCFConstants.AS_RAW_READ_POS_RANK_SUM_KEY, "|-2.1,1|NaN|NaN");
        origAttributes.put(GATKVCFConstants.AS_SB_TABLE_KEY, "17,18|8,5|0,0|0,1");
        origAttributes.put(GATKVCFConstants.AS_RAW_RMS_MAPPING_QUALITY_KEY, "123769.00|46800.00|0.00|0.00");
        originalBuilder.attributes(origAttributes);
        final VariantContext originalVC = originalBuilder.make();
        final Genotype newG = VariantContextTestUtils.makeG("sample1", LONG_REF, LONG_SNP, 41, 0, 37, 200, 100, 200, 400, 600, 800, 1200);
        final VariantContext regenotypedVC = makeDeletionVC("", Arrays.asList(LONG_REF, LONG_SNP, Allele.NON_REF_ALLELE), LONG_REF.length(), newG);

        final Map<String, Object> subsetAnnotations = ReblockGVCF.subsetAnnotationsIfNecessary(annotationEngine, true, null, originalVC, regenotypedVC);
        Assert.assertTrue(subsetAnnotations.containsKey(VCFConstants.DEPTH_KEY));
        Assert.assertEquals(subsetAnnotations.get(VCFConstants.DEPTH_KEY), 93);

        Assert.assertEquals(subsetAnnotations.get(GATKVCFConstants.RAW_MAPPING_QUALITY_WITH_DEPTH_KEY), "329810,93");
        Assert.assertEquals(subsetAnnotations.get(GATKVCFConstants.RAW_RMS_MAPPING_QUALITY_DEPRECATED), 329810.0);
        Assert.assertEquals(subsetAnnotations.get(GATKVCFConstants.MAPPING_QUALITY_DEPTH_DEPRECATED), 93);

        Assert.assertEquals(subsetAnnotations.get(GATKVCFConstants.AS_RAW_READ_POS_RANK_SUM_KEY), "|-2.1,1|NaN");
        Assert.assertEquals(subsetAnnotations.get(GATKVCFConstants.AS_SB_TABLE_KEY), "17,18|8,5|0,0");
        Assert.assertEquals(subsetAnnotations.get(GATKVCFConstants.AS_RAW_RMS_MAPPING_QUALITY_KEY), "123769.00|46800.00|0.00");
    }

    private GVCFWriter setUpWriter(final File outputFile, final File dictionary) throws IOException {
        final VariantContextWriterBuilder builder = new VariantContextWriterBuilder();
        builder.setOutputPath(outputFile.toPath());
        final SAMSequenceDictionary dict = SAMSequenceDictionaryExtractor.extractDictionary(dictionary.toPath());
        builder.setReferenceDictionary(dict);
        final VariantContextWriter vcfWriter = builder.build();
        final GVCFWriter gvcfWriter= new GVCFWriter(vcfWriter, Arrays.asList(20,100));
        final VCFHeader result = new VCFHeader(Collections.emptySet(), Collections.singletonList(VariantContextTestUtils.SAMPLE_NAME));
        result.setSequenceDictionary(dict);
        result.addMetaDataLine(new VCFFormatHeaderLine(VCFConstants.GENOTYPE_KEY, 1,
                VCFHeaderLineType.String,  "genotype"));
        result.addMetaDataLine(new VCFFormatHeaderLine(VCFConstants.GENOTYPE_ALLELE_DEPTHS, 1,
                VCFHeaderLineType.String, "Allele depth"));
        result.addMetaDataLine(new VCFFormatHeaderLine(VCFConstants.DEPTH_KEY, 1,
                VCFHeaderLineType.String, " depth"));
        result.addMetaDataLine(new VCFInfoHeaderLine(VCFConstants.DEPTH_KEY, 1,
                VCFHeaderLineType.String, " depth"));
        result.addMetaDataLine(new VCFFormatHeaderLine(VCFConstants.GENOTYPE_QUALITY_KEY, 1,
                VCFHeaderLineType.String, "Genotype quality"));
        result.addMetaDataLine(new VCFFormatHeaderLine(VCFConstants.GENOTYPE_PL_KEY, 1,
                VCFHeaderLineType.String, "Phred-scaled likelihoods"));
        gvcfWriter.writeHeader(result);
        return gvcfWriter;
    }

    private VariantContext makeDeletionVC(final String source, final List<Allele> alleles, final int refLength, final Genotype... genotypes) {
        final int start = 10;
        final int stop = start+refLength-1;
        return new VariantContextBuilder(source, "1", start, stop, alleles)
                .genotypes(Arrays.asList(genotypes)).unfiltered().log10PError(-3.0).attribute(VCFConstants.DEPTH_KEY, EXAMPLE_DP).make();
    }

    private VariantContext makeSNPVC(final String source, final List<Allele> alleles, final int refLength, final Genotype... genotypes) {
        final int start = 10;
        final int stop = start;
        return new VariantContextBuilder(source, "1", start, stop, alleles)
                .genotypes(Arrays.asList(genotypes)).unfiltered().make();
    }

    private Genotype addAD(final Genotype g, final int... ads) {
        return new GenotypeBuilder(g).AD(ads).make();
    }

    private VariantContext addAttributes(final VariantContext vc, final Map<String, Object> attributes) {
        return new VariantContextBuilder(vc).attributes(attributes).make();
    }

}