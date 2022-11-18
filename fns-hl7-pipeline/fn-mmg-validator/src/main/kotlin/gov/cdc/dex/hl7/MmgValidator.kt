package gov.cdc.dex.hl7

import gov.cdc.dex.azure.RedisProxy
import gov.cdc.dex.hl7.exception.InvalidConceptKey
import gov.cdc.dex.hl7.exception.InvalidMessageException
import gov.cdc.dex.hl7.model.*
import gov.cdc.dex.mmg.MmgUtil

import gov.cdc.dex.redisModels.Element
import gov.cdc.dex.redisModels.MMG
import gov.cdc.hl7.HL7StaticParser

import org.slf4j.LoggerFactory
import scala.Option


class MmgValidator {
    companion object {
        private val logger = LoggerFactory.getLogger(MmgValidator::class.java.simpleName)
        const val GENVx_PROFILE_PATH = "MSH-21[2].1"
        const val CONDITION_PROFILE_PATH = "MSH-21[3].1"
        const val EVENT_CODE_PATH = "OBR[@4.1='68991-9']-31.1"
        const val REPORTING_JURISDICTION_PATH = "OBX[@3.1='77968-6']-5.1"
    }
    val REDIS_NAME = System.getenv(RedisProxy.REDIS_CACHE_NAME_PROP_NAME)
    val REDIS_KEY  = System.getenv(RedisProxy.REDIS_PWD_PROP_NAME)
    val REDIS_VOCAB_NAMESPACE = "vocab:"

    private val redisProxy = RedisProxy(REDIS_NAME, REDIS_KEY)
    private val mmgUtil = MmgUtil(redisProxy)
    fun validate(hl7Message: String): List<ValidationIssue> {
        val mmgs = getMMGFromMessage(hl7Message)
        val report = mutableListOf<ValidationIssue>()

        validateMMGRules(hl7Message, mmgs, report)
        validateOtherSegments(hl7Message, mmgs, report)
        return report
    }

    fun validateMMGRules(hl7Message: String, mmgs: Array<MMG>, report: MutableList<ValidationIssue>) {
        val allBlocks:Int  =  mmgs.map { it.blocks.size }.sum()
        // logger.debug("validate started blocks.size: --> $allBlocks")
        mmgs.forEach { mmg ->
            mmg.blocks.forEach { block ->
                block.elements.forEach { element ->
                    //Cardinality Check!
                    val msgSegments = HL7StaticParser.getValue(hl7Message, element.getSegmentPath())
                    val valueList = if(msgSegments.isDefined)
                        msgSegments.get().flatten()
                    else listOf()
                    checkCardinality(hl7Message, block.type in listOf("Repeat", "RepeatParentChild"), element, valueList, report)
                    // Data type check: (Don't check Data type for Units of measure - fieldPosition is 6, not 5 - can't use isUnitOfMeasure field.)
                    if ("OBX" == element.mappings.hl7v251.segmentType && 5 == element.mappings.hl7v251.fieldPosition) {
                        val dataTypeSegments = HL7StaticParser.getListOfMatchingSegments(hl7Message, element.mappings.hl7v251.segmentType, getSegIdx(element))
                        for ( k in dataTypeSegments.keys().toList()) {
                           checkDataType(element, dataTypeSegments[k].get()[2], k.toString().toInt(), report )
                        }
                    }

                    if (msgSegments.isDefined)  {
                        val msgValues = HL7StaticParser.getValue(hl7Message, element.getValuePath())
                        if (msgValues.isDefined)
                            checkVocab(element,msgValues.get(), hl7Message, report)
                    }
                } // .for element
            } // .for block
        }// .for mmg
    } // .validate

    private fun validateOtherSegments(hl7Message: String, mmgs: Array<MMG>, report: MutableList<ValidationIssue> ) {
        val elementsByObxID = makeMMGsMapByObxID(mmgs)
        hl7Message.split("\n").forEachIndexed { lineNum, line ->

            val lineParts = line.split("|")
            val segmentName = lineParts[0]

            when (segmentName) {
                "MSH", "PID", "OBR" -> {}// nothing to do
//                "OBR" -> {
//                    val segmentID = lineParts[4].split("^")[0]
//                    //logger.info("hl7 segmentName: --> " + segmentName + " -- " + "segmentID: --> " + segmentID)
//                    if ( segmentID != OBR_4_1_EPI_ID ) {
//                        report += ValidationIssue(
//                            category= ValidationIssueCategoryType.WARNING,
//                            type= ValidationIssueType.SEGMENT_NOT_IN_MMG,
//                            fieldName=segmentName,
//                            hl7Path="N/A",
//                            lineNumber=lineNum + 1,
//                            errorMessage= ValidationErrorMessage.SEGMENT_NOT_IN_MMG,
//                            message="OBR segment 4.1 does not match the EPI identifier. Expected: ${OBR_4_1_EPI_ID}, Found: ${segmentID}",
//                        )
//                    }
//                } // .OBR
                "OBX" -> {
                    val segmentID = lineParts[3].split("^")[0]

                    if ( !elementsByObxID.containsKey(segmentID) ) {
                        report += ValidationIssue(
                            classification= ValidationIssueCategoryType.WARNING,
                            category= ValidationIssueType.SEGMENT_NOT_IN_MMG,
                            fieldName=segmentName,
                            path ="N/A",
                            line=lineNum + 1,
                            errorMessage= ValidationErrorMessage.SEGMENT_NOT_IN_MMG,
                            description="OBX segment identifier not found in the MMG. Found: ${segmentID}",
                        )
                    } // .if
                    //logger.info("hl7 segmentName: --> " + segmentName + " -- " + "segmentID: --> " + segmentID)
                } // .OBX
//                else -> {
//                    if (segmentName.trim() != "") { // empty line, should not happen
//                        report += ValidationIssue(
//                            classification= ValidationIssueCategoryType.WARNING,
//                            category= ValidationIssueType.SEGMENT_NOT_IN_MMG,
//                            fieldName=segmentName,
//                            path="N/A",
//                            line=lineNum + 1,
//                            errorMessage= ValidationErrorMessage.SEGMENT_NOT_IN_MMG,
//                            description="Segment does not match: MSH, PID, OBR, or OBX. Found: ${segmentName}",
//                        )
//                    } // .if
//                } // .else
            }

        } // hl7Message.split
    } // .validateOtherSegments


    private fun makeMMGsMapByObxID(mmgs: Array<MMG>): Map<String, Element> {
        val elementsByObxID = mutableMapOf<String, Element>()
        mmgs.forEach { mmg ->
            mmg.blocks.forEach { block ->
                block.elements.forEach { element ->
                    elementsByObxID[element.mappings.hl7v251.identifier] = element
                } // .for element
            } // .for block
        }// .for mmg
        return elementsByObxID.toMap()
    } // .makeMMGsMapByObxID

    private fun checkCardinality(hl7Message: String, blockRepeat: Boolean, element: Element, msgValues: List<String>, report:MutableList<ValidationIssue>) {
        val cardinality = element.mappings.hl7v251.cardinality
        val card1Re = """\d+|\*""".toRegex()
        val cards = card1Re.findAll(cardinality)
        val minCardinality = cards.elementAt(0).value
        val maxCardinality = cards.elementAt(1).value

        if (blockRepeat) { //cardinality must be checked within Blocks of OBX-4
            val allOBXs = msgValues.joinToString("\n")
            val uniqueGroups = HL7StaticParser.getValue(allOBXs, "OBX-4")
            if (uniqueGroups.isDefined) {
                uniqueGroups.get().flatten().distinct().forEach { groupID ->
                    val groupOBX = HL7StaticParser.getValue(allOBXs, "OBX[@4='$groupID']-5")
                    checkSingleGroupCardinaltiy(hl7Message, minCardinality, maxCardinality, groupID, element, groupOBX, report)
                }
            }
        } else {
            val allSegs = msgValues.joinToString("\n") //join all segments to extract all Values.
            val segValues = HL7StaticParser.getValue(allSegs, element.getValuePath())
//            val segValuesFlat = if (segValues.isDefined) segValues.get().flatten() else listOf()
            checkSingleGroupCardinaltiy(hl7Message, minCardinality, maxCardinality, null, element, segValues, report)

        }
    }
    private fun checkSingleGroupCardinaltiy(hl7Message: String, minCardinality: String, maxCardinality: String, groupID: String?,  element: Element, matchingSegs: Option<Array<Array<String>>>, report: MutableList<ValidationIssue>) {
        val values = if (matchingSegs.isDefined) matchingSegs.get().flatten() else listOf()
        if (minCardinality.toInt() > 0 && values.distinct().size < minCardinality.toInt()) {
            val matchingSegments = HL7StaticParser.getListOfMatchingSegments(hl7Message, element.mappings.hl7v251.segmentType, getSegIdx(element))
            val subList = if (groupID != null) {
                matchingSegments.filter { it._2[4] == groupID}
            } else matchingSegments
            val lineNbr = if (subList.size() >0 ) {
                subList.keys().toList().last().toString().toInt()
            } else 0
            report += ValidationIssue(
                classification= getCategory(element.mappings.hl7v251.usage),
                category= ValidationIssueType.CARDINALITY,
                fieldName=element.name,
                path=element.getValuePath(),
                line=lineNbr, //Get the last Occurrence of line number
                errorMessage= ValidationErrorMessage.CARDINALITY_UNDER, // CARDINALITY_OVER
                description="Minimum required value not present. Requires $minCardinality, Found ${values.size}",
            ) // .ValidationIssue
        }

        when (maxCardinality) {
            "*" -> "Unbounded"
            else -> if (values.distinct().size > maxCardinality.toInt()) {
                val matchingSegments = HL7StaticParser.getListOfMatchingSegments(hl7Message, element.mappings.hl7v251.segmentType, getSegIdx(element))
                val subList = if (groupID != null) {
                     matchingSegments.filter { it._2[4] == groupID}
                } else matchingSegments
                report += ValidationIssue(
                    classification= getCategory(element.mappings.hl7v251.usage),
                    category= ValidationIssueType.CARDINALITY,
                    fieldName=element.name,
                    path=element.getValuePath(),
                    line=subList.keys().toList().last().toString().toInt(),
                    errorMessage= ValidationErrorMessage.CARDINALITY_OVER, // CARDINALITY_OVER
                    description="Maximum values surpassed requirements. Max allowed: $maxCardinality, Found ${values.size}",
                ) // .ValidationIssue
            }
        }
    } // .checkCardinality 

    private fun checkDataType(element: Element, msgDataType: String?, lineNbr: Int, report: MutableList<ValidationIssue>) {
        if (msgDataType != null && msgDataType != element.mappings.hl7v251.dataType) {
                report += ValidationIssue(
                    classification= getCategory(element.mappings.hl7v251.usage),
                    category= ValidationIssueType.DATA_TYPE,
                    fieldName=element.name,
                    path=element.getDataTypePath(),
                    line=lineNbr, //Data types only have single value.
                    errorMessage= ValidationErrorMessage.DATA_TYPE_MISMATCH, // DATA_TYPE_MISMATCH
                    description="Data type on message does not match expected data type on MMG. Expected: ${element.mappings.hl7v251.dataType}, Found: ${msgDataType}",
                )
        }

    } // .checkDataType

    private fun checkVocab(elem: Element, msgValues: Array<Array<String>>, message: String, report:MutableList<ValidationIssue> ) {
        if (!elem.valueSetCode.isNullOrEmpty() && "N/A" != elem.valueSetCode) {
            msgValues.forEachIndexed { outIdx, outArray ->
                outArray.forEachIndexed { _, inElem ->
                    //if (concepts.filter { it.conceptCode == inElem }.isEmpty()) {
                    if (!isConceptValid(elem.valueSetCode!!, inElem)) {
                        val lineNbr = getLineNumber(message, elem, outIdx)
                        val issue = ValidationIssue(
                            getCategory(elem.mappings.hl7v251.usage),
                            ValidationIssueType.VOCAB,
                            elem.name,
                            elem.getValuePath(),
                            lineNbr,
                            ValidationErrorMessage.VOCAB_ISSUE,
                            "Unable to find '$inElem' on '${elem.valueSetCode}' on line $lineNbr"
                        )
                        report.add(issue)
                    }
                }//.forEach Inner Array
            } //.forEach Outer Array
        }
    }

    //Some Look ps are reused - storing them so no need to re-download them from Redis.
    private val valueSetMap = mutableMapOf<String, List<String>>()
    //    private val mapper = jacksonObjectMapper()
    @Throws(InvalidConceptKey::class)
    fun isConceptValid(key: String, concept: String): Boolean {
        if (valueSetMap[key] === null) {
            // logger.debug("Retrieving $key from Redis")
            val conceptStr = redisProxy.getJedisClient().hgetAll(REDIS_VOCAB_NAMESPACE + key)
            //Keys comes from MMG definitions and therefore SHOULD exist on REDIS.
            //IF not the env. is misconfigured and we cannot proceed validating messages
            if (conceptStr.isNullOrEmpty())
                throw InvalidConceptKey("Unable to retrieve concept values for $key")
            valueSetMap[key] = conceptStr.keys.toList()

        }
        return valueSetMap[key]?.filter { it == concept }?.isNotEmpty() ?: false
    }

    private fun getCategory(usage: String): ValidationIssueCategoryType {
        return when (usage) {
            "R" -> ValidationIssueCategoryType.ERROR
            else -> ValidationIssueCategoryType.WARNING
        }
    } // .getCategory

    private fun getSegIdx(elem: Element): String {
        return when (elem.mappings.hl7v251.segmentType) {
            "OBX" -> "@3.1='${elem.mappings.hl7v251.identifier}'"
            else -> "1"
        }
    }
    private fun getLineNumber(message: String, elem: Element, outArrayIndex: Int): Int {
        val allSegs = HL7StaticParser.getListOfMatchingSegments(message, elem.mappings.hl7v251.segmentType, getSegIdx(elem))
        var line = 0
        var forBreak = 0
        for ( k in allSegs.keys().toList()) {
            line = k as Int
            if (forBreak >= outArrayIndex) break
            forBreak++
        }
        return line
    }

    private fun getMMGFromMessage(message: String): Array<MMG> {
        val genVProfile = this.extractValue(message, GENVx_PROFILE_PATH)
        val conditionProfile = this.extractValue(message, CONDITION_PROFILE_PATH)
        val eventCode = this.extractValue(message, EVENT_CODE_PATH)
        val jurisdictionCode = this.extractValue(message,REPORTING_JURISDICTION_PATH)
        logger.info("Profiles for Message --> GenV2: $genVProfile, Condition Specific: $conditionProfile, Event Code:$eventCode")
        return mmgUtil.getMMG(genVProfile, conditionProfile, eventCode, jurisdictionCode)
    }
    private fun extractValue(msg: String, path: String):String  {
        val value = HL7StaticParser.getFirstValue(msg, path)
        return if (value.isDefined) value.get() //throw InvalidMessageException("Error extracting $path from HL7 message")
        else ""
    }
} // .MmgValidator