var CodePush = require("code-push");

if (process.argv.length < 7)
    throw Error('usage: node CodePushRelease.js <app center access token> <app name> <deployment name> <update contents path> <target binary version>');

let accessKey = process.argv[2];
let appName = process.argv[3];
let deploymentName = process.argv[4];
let updateContentsPath = process.argv[5];
let targetBinaryVersion = process.argv[6];
let updateMetadata = {};
let codePush = new CodePush(accessKey);

codePush.release(appName, deploymentName, updateContentsPath, targetBinaryVersion, updateMetadata)
    .then(result => {
        console.log('release result:');
        console.dir(result);
    },
        error => {
            console.log('failed to release:');
            console.dir(error);
            if (error.statusCode = 409) {
                console.log(error.message); // The uploaded package was not released because it is identical to the contents of the specified deployment's current release
            } else {
                process.exit(1);
            }
        });
