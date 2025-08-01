#!/usr/bin/env groovy

// Copyright (C) 2019 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

// Scripted pipeline for building and testing Gerrit.
//
// Jenkinsfile example:
// gerritPipeline()

import groovy.json.JsonSlurper
import groovy.json.JsonOutput

def call(Map parm = [:]) {
    node ('server') {

        if (hasChangeNumber()) {
            stage('Preparing'){
                collectBuildModes()
            }
        }

        parallel(collectBuilds())

        if (hasChangeNumber()) {
            stage('Retry Flaky Builds'){
                def flakyBuildsModes = findFlakyBuilds()
                if (flakyBuildsModes.size() > 0){
                    parallel flakyBuildsModes.collectEntries {
                        ["Gerrit-verification(${it})" :
                            prepareBuildsForMode(buildVerificationJob(), it, 3)]
                    }
                }
            }

            stage('Report to Gerrit'){
                resCodeStyle = getLabelValue(1, Builds.codeStyle.result)
                gerritReview labels: ['Code-Style': resCodeStyle]

                def verificationResults = Builds.verification.collect { k, v -> v }
                def resVerify = verificationResults.inject(1) {
                    acc, build -> getLabelValue(acc, build.result)
                }
                gerritReview labels: ['Verified': resVerify]

                setResult(resVerify, resCodeStyle)
            }
        }
    }
}

class Globals {
    static final String gerritUrl = "https://gerrit-review.googlesource.com/"
    static final String gerritCredentialsId = "gerrit-review.googlesource.com"
    static final long curlTimeout = 10000
    static final int waitForResultTimeout = 10000
    static final String gerritRepositoryNameSha1Suffix = "-a6a0e4682515f3521897c5f950d1394f4619d928"
}

class Build {
    String url
    String result

    Build(url, result) {
        this.url = url
        this.result = result
    }
}

class Builds {
    static Set<String> modes = []
    static Build codeStyle = null
    static Map verification = [:]
}

class GerritCheck {
    String uuid
    Build build
    String consoleUrl

    GerritCheck(name, build) {
        this.uuid = "gerritforge:" + name.replaceAll("(bazel/)", "") +
            Globals.gerritRepositoryNameSha1Suffix
        this.build = build
        this.consoleUrl = "${build.url}console"
    }

    def getCheckResultFromBuild() {
        if (!build.getResult()) {
            return "RUNNING"
        }
        def resultString = build.getResult().toString()
        if (resultString == 'SUCCESS') {
            return "SUCCESSFUL"
        } else if (resultString == 'NOT_BUILT' || resultString == 'ABORTED') {
            return "NOT_STARTED"
        }

        // Remaining options: 'FAILURE' or 'UNSTABLE':
        return "FAILED"
    }
}

def hasChangeNumber() {
    env.GERRIT_CHANGE_NUMBER?.trim()
}

def postCheck(check) {
    gerritCheck(checks: [ "${check.uuid}" : "${check.getCheckResultFromBuild()}" ], url: "${check.consoleUrl}")
}

def queryChangedFiles(url) {
    def queryUrl = "${url}a/changes/${env.GERRIT_CHANGE_NUMBER}/revisions/${env.GERRIT_PATCHSET_REVISION}/files/"
    def response = httpRequest(url: queryUrl, authentication: env.GERRIT_CREDENTIALS_ID)
    def files = response.getContent().substring(5)
    def filesJson = new JsonSlurper().parseText(files)
    return filesJson.keySet().findAll { it != "/COMMIT_MSG" }
}

def collectBuildModes() {
    Builds.modes = []
    if (env.GERRIT_BRANCH == "master" || env.GERRIT_BRANCH ==~ /stable-3.1[0-2]/) {
        Builds.modes = ["notedb", "rbe"]
    } else {
        throw new Exception("Unsupported branch ${env.GERRIT_BRANCH}")
    }

    def changedFiles = queryChangedFiles(Globals.gerritUrl)
    def isMerge = changedFiles.contains("/MERGE_LIST")
    def polygerritFiles = changedFiles.findAll { it.startsWith("polygerrit-ui") ||
        it.startsWith("lib/js") }
    def bazelFiles = changedFiles.findAll { it == "WORKSPACE" || it.endsWith("BUILD") ||
        it.endsWith(".bzl") || it == ".bazelversion" }
    if(isMerge) {
        println "Merge commit detected, adding 'polygerrit' validation..."
        Builds.modes += "polygerrit"
    } else if(polygerritFiles.size() > 0) {
        if(changedFiles.size() == polygerritFiles.size() && bazelFiles.isEmpty()) {
            println "Only PolyGerrit UI changes detected, skipping other test modes..."
            Builds.modes = ["polygerrit"]
        } else {
            println "PolyGerrit UI changes detected, adding 'polygerrit' validation..."
            Builds.modes += "polygerrit"
        }
    } else if(!bazelFiles.isEmpty()) {
        println "Bazel files changes detected, adding 'polygerrit' validation..."
        Builds.modes += "polygerrit"
    }
}

def buildVerificationJob() {
    "Gerrit-verifier-chrome-latest"
}

def prepareBuildsForMode(buildName, mode="notedb", retryTimes = 1) {
    return {
        stage("${buildName}/${mode}") {
            def agentBuild = null
            for (int i = 1; i <= retryTimes; i++) {
                postCheck(new GerritCheck(
                    (buildName == "Gerrit-codestyle") ? "codestyle" : mode,
                    new Build(currentBuild.getAbsoluteUrl(), null)))
                try {
                    agentBuild = build job: "${buildName}", parameters: [
                        string(name: 'REFSPEC', value: "refs/changes/${env.BRANCH_NAME}"),
                        string(name: 'BRANCH', value: env.GERRIT_PATCHSET_REVISION),
                        string(name: 'CHANGE_URL', value: "${Globals.gerritUrl}c/${env.GERRIT_PROJECT}/+/${env.GERRIT_CHANGE_NUMBER}"),
                        string(name: 'MODE', value: mode),
                        string(name: 'TARGET_BRANCH', value: env.GERRIT_BRANCH)
                    ], propagate: false
                } finally {
                    if (buildName == "Gerrit-codestyle"){
                        Builds.codeStyle = new Build(
                            agentBuild.getAbsoluteUrl(), agentBuild.getResult())
                        postCheck(new GerritCheck("codestyle", Builds.codeStyle))
                    } else {
                        Builds.verification[mode] = new Build(
                            agentBuild.getAbsoluteUrl(), agentBuild.getResult())
                        postCheck(new GerritCheck(mode, Builds.verification[mode]))
                    }
                    if (agentBuild.getResult() == "SUCCESS") {
                        break
                    }
                }
            }
        }
    }
}

def collectBuilds() {
    def builds = [:]
    if (hasChangeNumber()) {
       builds["Gerrit-codestyle"] = prepareBuildsForMode("Gerrit-codestyle")
       Builds.modes.each {
          builds["Gerrit-verification(${it})"] = prepareBuildsForMode((buildVerificationJob()), it)
       }
    } else {
       builds["java"] = { -> build "Gerrit-bazel-${env.BRANCH_NAME}" }
    }
    return builds
}

def findFlakyBuilds() {
    def flaky = Builds.verification.findAll { it.value.result == null ||
        it.value.result != 'SUCCESS' }

    if(flaky.size() == Builds.verification.size()) {
        return []
    }

    def retryBuilds = []
    flaky.each {
        def mode = it.key
        Builds.verification = Builds.verification.findAll { it.key != mode }
        retryBuilds += mode
    }

    return retryBuilds
}

def getLabelValue(acc, res) {
    if(res == null || res == 'ABORTED') {
        return 0
    }
    switch(acc) {
        case 0: return 0
        case 1:
            if(res == null) {
                return 0
            }
            switch(res) {
                case 'SUCCESS': return +1
                case 'FAILURE': return -1
                default: return 0
            }
        case -1: return -1
    }
}

def setResult(resultVerify, resultCodeStyle) {
    if (resultVerify == 0 || resultCodeStyle == 0) {
        currentBuild.result = 'ABORTED'
    } else if (resultVerify == -1 || resultCodeStyle == -1) {
        currentBuild.result = 'FAILURE'
    } else {
        currentBuild.result = 'SUCCESS'
    }
}

def findCodestyleFilesInLog(build) {
    def codeStyleFiles = []
    def needsFormatting = false
    def response = httpRequest "${build.url}consoleText"
    response.content.eachLine {
        needsFormatting = needsFormatting || (it ==~ /.*Need Formatting.*/)
        if(needsFormatting && it ==~ /\[.*\]/) {
            codeStyleFiles += it.substring(1,it.length()-1)
        }
    }

    return codeStyleFiles
}
