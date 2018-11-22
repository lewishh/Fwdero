"use strict";

angular.module('demoAppModule').controller('CreateForwardModalCtrl', function($http, $uibModalInstance, $uibModal, apiBaseURL, peers) {
    const createForwardModal = this;

    createForwardModal.peers = peers;
    createForwardModal.form = {};
    createForwardModal.formError = false;

    /** Validate and create an Forward. */
    createForwardModal.create = () => {
        if (invalidFormInput()) {
            createForwardModal.formError = true;
        } else {
            createForwardModal.formError = false;

            const acceptor = createForwardModal.form.acceptor;
            const instrument = createForwardModal.form.instrument;
            const quantity = createForwardModal.form.quantity;
            const currency = createForwardModal.form.currency;
            const amount = createForwardModal.form.amount;
            const timestamp = createForwardModal.form.timestamp;
            const type = createForwardModal.form.type;

            $uibModalInstance.close();

            // We define the Forward creation endpoint.
            const issueForwardEndpoint =
                apiBaseURL +
                `create?acceptor=${acceptor}&instrument=${instrument}&quantity=${quantity}&currency=${currency}&amount=${amount}&timestamp=${timestamp}&type=${type}`;

            // We hit the endpoint to create the Forward and handle success/failure responses.
            $http.get(issueForwardEndpoint).then(
                (result) => createForwardModal.displayMessage(result),
                (result) => createForwardModal.displayMessage(result)
            );
        }
    };

    /** Displays the success/failure response from attempting to create an Forward. */
    createForwardModal.displayMessage = (message) => {
        const createForwardMsgModal = $uibModal.open({
            templateUrl: 'createForwardMsgModal.html',
            controller: 'createForwardMsgModalCtrl',
            controllerAs: 'createForwardMsgModal',
            resolve: {
                message: () => message
            }
        });

        // No behaviour on close / dismiss.
        createForwardMsgModal.result.then(() => {}, () => {});
    };

    /** Closes the Forward creation modal. */
    createForwardModal.cancel = () => $uibModalInstance.dismiss();

    // Validates the Forward.
    function invalidFormInput() {
        return isNaN(createForwardModal.form.amount) || (createForwardModal.form.acceptor === undefined);
    }
});

// Controller for the success/fail modal.
angular.module('demoAppModule').controller('createForwardMsgModalCtrl', function($uibModalInstance, message) {
    const createForwardMsgModal = this;
    createForwardMsgModal.message = message.data;
});