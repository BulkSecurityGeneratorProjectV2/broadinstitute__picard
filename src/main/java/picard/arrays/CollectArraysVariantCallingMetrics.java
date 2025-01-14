package picard.arrays;

import org.apache.commons.lang.StringUtils;
import org.broadinstitute.barclay.help.DocumentedFeature;
import picard.analysis.MergeableMetricBase;
import picard.arrays.illumina.ArraysControlInfo;
import htsjdk.samtools.SAMSequenceDictionary;
import htsjdk.samtools.metrics.MetricBase;
import htsjdk.samtools.metrics.MetricsFile;
import htsjdk.samtools.util.CloserUtil;
import htsjdk.samtools.util.IOUtil;
import htsjdk.samtools.util.Iso8601Date;
import htsjdk.samtools.util.Log;
import htsjdk.variant.utils.SAMSequenceDictionaryExtractor;
import htsjdk.variant.vcf.VCFFileReader;
import htsjdk.variant.vcf.VCFHeader;
import htsjdk.variant.vcf.VCFHeaderLine;
import org.broadinstitute.barclay.argparser.Argument;
import org.broadinstitute.barclay.argparser.CommandLineProgramProperties;
import picard.cmdline.CommandLineProgram;
import picard.cmdline.StandardOptionDefinitions;
import picard.cmdline.programgroups.DiagnosticsAndQCProgramGroup;
import picard.pedigree.Sex;
import picard.util.DbSnpBitSetUtil;
import picard.util.help.HelpConstants;
import picard.vcf.processor.VariantProcessor;

import java.io.File;
import java.util.*;

/**
 * Collects summary and per-sample metrics about variant calls in a VCF file.
 */
@CommandLineProgramProperties(
        summary = CollectArraysVariantCallingMetrics.USAGE_DETAILS,
        oneLineSummary = "Collects summary and per-sample from the provided arrays VCF file",
        programGroup = DiagnosticsAndQCProgramGroup.class
)
@DocumentedFeature
public class CollectArraysVariantCallingMetrics extends CommandLineProgram {
    static final String USAGE_DETAILS =
            "CollectArraysVariantCallingMetrics takes a Genotyping Arrays VCF file (as generated by GtcToVcf) and calculates " +
                    "summary and per-sample metrics. " +
                    "<h4>Usage example:</h4>" +
                    "<pre>" +
                    "java -jar picard.jar CollectArraysVariantCallingMetrics \\<br />" +
                    "      INPUT=genotyping_arrays.vcf \\<br />" +
                    "      OUTPUT=outputBaseName" +
                    "</pre>";


    @Argument(shortName = StandardOptionDefinitions.INPUT_SHORT_NAME, doc = "Input vcf file for analysis")
    public File INPUT;

    @Argument(shortName = StandardOptionDefinitions.OUTPUT_SHORT_NAME, doc = "Path (except for the file extension) of output metrics files " +
            "to write.")
    public File OUTPUT;

    @Argument(doc = "The Call Rate Threshold for an autocall pass (if the observed call rate is > this value, the sample is considered to be passing)", optional = true)
    public static Double CALL_RATE_PF_THRESHOLD = 0.98;

    @Argument(doc = "Reference dbSNP file in dbSNP or VCF format.")
    public File DBSNP;

    @Argument(shortName = StandardOptionDefinitions.SEQUENCE_DICTIONARY_SHORT_NAME, optional = true,
            doc = "If present, speeds loading of dbSNP file, will look for dictionary in vcf if not present here.")
    public File SEQUENCE_DICTIONARY = null;

    @Argument(doc = "Split this task over multiple threads.  If NUM_PROCESSORS = 0, number of cores is automatically set to " +
            "the number of cores available on the machine. If NUM_PROCESSORS < 0 then the number of cores used will be " +
            "the number available on the machine less NUM_PROCESSORS.")
    public int NUM_PROCESSORS = 0;

    private final Log log = Log.getInstance(CollectArraysVariantCallingMetrics.class);

    @Override
    protected int doWork() {

        IOUtil.assertFileIsReadable(INPUT);
        IOUtil.assertFileIsReadable(DBSNP);
        if (SEQUENCE_DICTIONARY != null) IOUtil.assertFileIsReadable(SEQUENCE_DICTIONARY);

        final VCFFileReader variantReader = new VCFFileReader(INPUT, true);
        final VCFHeader vcfHeader = variantReader.getFileHeader();
        CloserUtil.close(variantReader);

        final SAMSequenceDictionary sequenceDictionary =
                SAMSequenceDictionaryExtractor.extractDictionary(SEQUENCE_DICTIONARY == null ? INPUT.toPath() : SEQUENCE_DICTIONARY.toPath());

        log.info("Loading dbSNP file.");
        final DbSnpBitSetUtil.DbSnpBitSets dbsnp = DbSnpBitSetUtil.createSnpAndIndelBitSets(DBSNP, sequenceDictionary);

        log.info("Starting iteration of variants.");

        ArraysControlInfo[] controlInfos = new ArraysControlInfo[ArraysControlInfo.CONTROL_INFO.length];
        for (int i = 0; i < ArraysControlInfo.CONTROL_INFO.length; i++) {
            controlInfos[i] = parseControlHeaderString(getValueFromVcfOtherHeaderLine(vcfHeader, ArraysControlInfo.CONTROL_INFO[i].getControl()));
        }

        final MetricsFile<CollectArraysVariantCallingMetrics.ArraysControlCodesSummaryMetrics, Integer> controlSummary = getMetricsFile();

        for (ArraysControlInfo info : controlInfos) {
            controlSummary.addMetric(new ArraysControlCodesSummaryMetrics(info.getControl(),
                    info.getCategory(), info.getRed(), info.getGreen()));
        }

        final VariantProcessor.Builder<ArraysCallingMetricAccumulator, ArraysCallingMetricAccumulator.Result> builder =
                VariantProcessor.Builder
                        .generatingAccumulatorsBy(() -> {
                            ArraysCallingMetricAccumulator accumulator = new ArraysCallingMetricAccumulator(dbsnp);
                            accumulator.setup(vcfHeader);
                            return accumulator;
                        })
                        .combiningResultsBy(ArraysCallingMetricAccumulator.Result::merge)
                        .withInput(INPUT)
                        .multithreadingBy(NUM_PROCESSORS);

        final ArraysCallingMetricAccumulator.Result result = builder.build().process();

        // Fetch and write the metrics.
        final MetricsFile<CollectArraysVariantCallingMetrics.ArraysVariantCallingDetailMetrics, Integer> detail = getMetricsFile();
        final MetricsFile<CollectArraysVariantCallingMetrics.ArraysVariantCallingSummaryMetrics, Integer> summary = getMetricsFile();
        summary.addMetric(result.summary);
        result.details.forEach(detail::addMetric);

        final String outputPrefix = OUTPUT.getAbsolutePath() + ".";
        detail.write(new File(outputPrefix + CollectArraysVariantCallingMetrics.ArraysVariantCallingDetailMetrics.getFileExtension()));
        summary.write(new File(outputPrefix + ArraysVariantCallingSummaryMetrics.getFileExtension()));
        controlSummary.write(new File(outputPrefix + CollectArraysVariantCallingMetrics.ArraysControlCodesSummaryMetrics.getFileExtension()));

        return 0;
    }

    private ArraysControlInfo parseControlHeaderString(String controlString) {
        String[] tokens = controlString.split("\\|");
        return new ArraysControlInfo(tokens[0], tokens[1], Integer.valueOf(tokens[2]), Integer.valueOf(tokens[3]));
    }

    private String getValueFromVcfOtherHeaderLine(final VCFHeader vcfHeader, final String keyName) {
        VCFHeaderLine otherHeaderLine = vcfHeader.getOtherHeaderLine(keyName);
        if (otherHeaderLine != null) {
            return otherHeaderLine.getValue();
        } else {
            throw new IllegalArgumentException("Input VCF file is missing header line of type '" + keyName + "'");
        }
    }

    @Override
    protected String[] customCommandLineValidation() {
        //If num processors is 0 set it to max. If it is negative subtract it from the max.
        if (NUM_PROCESSORS == 0) {
            NUM_PROCESSORS = Runtime.getRuntime().availableProcessors();
        } else if (NUM_PROCESSORS < 0) {
            NUM_PROCESSORS = Runtime.getRuntime().availableProcessors() + NUM_PROCESSORS;
        }

        //sanity check if num processors is negative set it to 1
        if (NUM_PROCESSORS <= 0) {
            NUM_PROCESSORS = 1;
        }

        if (CALL_RATE_PF_THRESHOLD <= 0 || CALL_RATE_PF_THRESHOLD > 1.0) {
            return new String[]{"The parameter CALL_RATE_PF_THRESHOLD must be > 0 and <= 1.0"};
        }
        return super.customCommandLineValidation();
    }


    @DocumentedFeature(groupName = HelpConstants.DOC_CAT_METRICS, summary = HelpConstants.DOC_CAT_METRICS_SUMMARY)
    public static class ArraysVariantCallingSummaryMetrics extends MergeableMetricBase {
        /**
         * The total number of assays (SNP and indels) in the VCF
         */
        @MergeByAdding
        public long NUM_ASSAYS;

        /**
         * The total number of NON-filtered assays in the VCF
         */
        @MergeByAdding
        public long NUM_NON_FILTERED_ASSAYS;

        /**
         * The total number of filtered assays in the VCF
         */
        @MergeByAdding
        public long NUM_FILTERED_ASSAYS;

        /**
         * The total number of zeroed-out (filtered out by Illumina in chip design) assays in the VCF
         */
        @MergeByAdding
        public long NUM_ZEROED_OUT_ASSAYS;

        /**
         * The number of bi-allelic SNP calls
         */
        @MergeByAdding
        public long NUM_SNPS;

        /**
         * The number of indel calls
         */
        @MergeByAdding
        public long NUM_INDELS;

        /**
         * The number of passing calls
         */
        @MergeByAdding
        public long NUM_CALLS;

        /**
         * The number of passing autocall calls
         */
        @MergeByAdding
        public long NUM_AUTOCALL_CALLS;

        /**
         * The number of no-calls
         */
        @MergeByAdding
        public long NUM_NO_CALLS;

        /**
         * The number of high confidence SNPs found in dbSNP
         */
        @MergeByAdding
        public long NUM_IN_DB_SNP;

        /**
         * The number of high confidence SNPS called that were not found in dbSNP
         */
        @MergeByAdding
        public long NOVEL_SNPS;

        /**
         * The fraction of high confidence SNPs in dbSNP
         */
        @NoMergingIsDerived
        public float PCT_DBSNP;

        /**
         * The overall call rate
         */
        @NoMergingIsDerived
        public float CALL_RATE;

        /**
         * The overall autocall call rate
         */
        @NoMergingIsDerived
        public float AUTOCALL_CALL_RATE;

        /**
         * For summary metrics, the number of variants that appear in only one sample.
         * For detail metrics, the number of variants that appear only in the current sample.
         */
        @MergeByAdding
        public long NUM_SINGLETONS;

        public static String getFileExtension() { return("arrays_variant_calling_summary_metrics"); }

        public void calculateDerivedFields() {
            this.PCT_DBSNP = this.NUM_IN_DB_SNP / (float) this.NUM_SNPS;
            this.NOVEL_SNPS = this.NUM_SNPS - this.NUM_IN_DB_SNP;
            this.CALL_RATE = this.NUM_CALLS / (float) this.NUM_NON_FILTERED_ASSAYS;
            this.AUTOCALL_CALL_RATE = this.NUM_AUTOCALL_CALLS / (float) this.NUM_NON_FILTERED_ASSAYS;
        }

        public static <T extends ArraysVariantCallingSummaryMetrics> void foldInto(final ArraysVariantCallingSummaryMetrics target, final Collection<ArraysVariantCallingDetailMetrics> metrics) {
            metrics.forEach(target::merge);
        }

    }

    @DocumentedFeature(groupName = HelpConstants.DOC_CAT_METRICS, summary = HelpConstants.DOC_CAT_METRICS_SUMMARY)
    public static class ArraysControlCodesSummaryMetrics extends MetricBase {

        /**
         * Various control intensities that are present on the infinium chips.
         *
         * @see picard.arrays.illumina.ArraysControlInfo#CONTROL_INFO
         */
        public String CONTROL;

        /**
         * Grouping categories for controls.
         */
        public String CATEGORY;

        /**
         * The red intensity value for the control.
         */
        public int RED;

        /**
         * The green intensity value for the control.
         */
        public int GREEN;


        //metrics uploader needs empty constructor
        public ArraysControlCodesSummaryMetrics() {
        }

        ArraysControlCodesSummaryMetrics(String control, String category, int red, int green) {
            CONTROL = control;
            CATEGORY = category;
            RED = red;
            GREEN = green;
        }

        public static String getFileExtension() { return("arrays_control_code_summary_metrics"); }

    }

    @DocumentedFeature(groupName = HelpConstants.DOC_CAT_METRICS, summary = HelpConstants.DOC_CAT_METRICS_SUMMARY)
    public static class ArraysVariantCallingDetailMetrics extends CollectArraysVariantCallingMetrics.ArraysVariantCallingSummaryMetrics {
        /**
         * The chip well barcode of the Illumina array being assayed
         */
        @MergeByAssertEquals
        public String CHIP_WELL_BARCODE;

        /**
         * The name of the sample
         */
        @MergeByAssertEquals
        public String SAMPLE_ALIAS;

        /**
         * The version number of the analysis run
         */
        @MergeByAssertEquals
        public Integer ANALYSIS_VERSION;

        /**
         * The chip type name
         */
        @MergeByAssertEquals
        public String CHIP_TYPE;

        /**
         * Whether the sample passes QC based on call rate threshold
         */
        @NoMergingIsDerived
        public Boolean AUTOCALL_PF;

        /**
         * The date this sample was autocalled
         */
        @MergeByAssertEquals
        public Iso8601Date AUTOCALL_DATE;

        /**
         * The date that this sample was imaged
         */
        @MergeByAssertEquals
        public Iso8601Date IMAGING_DATE;

        /**
         * Whether the sample was zcalled
         */
        @NoMergingIsDerived
        public Boolean IS_ZCALLED;

        /**
         * The call rate as determined by Autocall/IAAP (and stored in the GTC File)
         */
        @MergeByAssertEquals
        public Double GTC_CALL_RATE;

        /**
         * The sex, as determined by Autocall
         */
        @MergeByAssertEquals
        public String AUTOCALL_GENDER;

        /**
         * The sex, as reported for the fingerprinted sample
         */
        @MergeByAssertEquals
        public String FP_GENDER;

        /**
         * The sex, as reported by the collaborator
         */
        @MergeByAssertEquals
        public String REPORTED_GENDER;

        /**
         * Whether or not the three sexs agree
         */
        @MergeByAssertEquals
        public Boolean GENDER_CONCORDANCE_PF;

        /**
         * 100 * (count of hets) / (total calls) for this sample
         */
        @NoMergingIsDerived
        public double HET_PCT;

        /**
         * The name of the cluster file used
         */
        @MergeByAssertEquals
        public String CLUSTER_FILE_NAME;

        /**
         * The 95th Intensity Percentile for the green color channel for this sample
         */
        @MergeByAssertEquals
        public Integer P95_GREEN;

        /**
         * The 95th Intensity Percentile for the red color channel for this sample
         */
        @MergeByAssertEquals
        public Integer P95_RED;

        /**
         * The version of autocall used for calling this sample
         */
        @MergeByAssertEquals
        public String AUTOCALL_VERSION;

        /**
         * The version of ZCall used for calling this sample
         */
        @MergeByAssertEquals
        public String ZCALL_VERSION;

        /**
         * The version of the Extended Illumina Manifest used for calling this sample
         */
        @MergeByAssertEquals
        public String EXTENDED_MANIFEST_VERSION;

        /**
         * (count of hets)/(count of homozygous non-ref) for this sample
         */
        @NoMergingIsDerived
        public double HET_HOMVAR_RATIO;

        /**
         * The name of the scanner used for this sample
         */
        @MergeByAssertEquals
        public String SCANNER_NAME;

        /**
         * The version of the pipeline used for this sample
         */
        @MergeByAssertEquals
        public String PIPELINE_VERSION;

        /**
         * Hidden fields not propagated to the metrics file.
         */
        @MergeByAdding
        long numHets, numHomVar;

        @MergeByAssertEquals
        String zcallThresholdsFile;

        @Override
        public void calculateDerivedFields() {
            super.calculateDerivedFields();
            HET_HOMVAR_RATIO = numHets / (double) numHomVar;
            HET_PCT = numHets / (double) NUM_CALLS;
            GENDER_CONCORDANCE_PF = getSexConcordance(REPORTED_GENDER, FP_GENDER, AUTOCALL_GENDER);
            IS_ZCALLED = !StringUtils.isEmpty(zcallThresholdsFile);
            AUTOCALL_PF = AUTOCALL_CALL_RATE > CALL_RATE_PF_THRESHOLD;
        }

        public static String getFileExtension() { return("arrays_variant_calling_detail_metrics"); }
    }

    public static boolean getSexConcordance(String reportedSexString, String fingerprintSexString, String autocallSexString) {
        Sex reportedSex = Sex.fromString(reportedSexString);
        Sex fingerprintSex = Sex.fromString(fingerprintSexString);
        Sex autocallSex = Sex.fromString(autocallSexString);
        Map<Sex, Integer> sexMatchCount = new EnumMap<>(Sex.class);
        for (Sex sex : Sex.values()) {
            sexMatchCount.put(sex, 0);
        }
        sexMatchCount.put(reportedSex, sexMatchCount.get(reportedSex) + 1);
        sexMatchCount.put(fingerprintSex, sexMatchCount.get(fingerprintSex) + 1);
        sexMatchCount.put(autocallSex, sexMatchCount.get(autocallSex) + 1);
        if (sexMatchCount.get(Sex.Unknown) + sexMatchCount.get(Sex.NotReported) == 3) {
            return false;           // All three sexs were unknown or not reported.  That's a fail
        } else
            return ((sexMatchCount.get(Sex.Female) > 1) && (sexMatchCount.get(Sex.Male) == 0)) ||
                    ((sexMatchCount.get(Sex.Male) > 1) && (sexMatchCount.get(Sex.Female) == 0));
    }
}
