"""Check that the audit separates submitted requests from completed operations."""

import unittest

import e2ee_lab_audit as audit


class LabAuditTest(unittest.TestCase):
    def test_completed_at_requires_a_successful_matching_response(self):
        request = {"kind": "request", "callId": "call-1", "timestampMs": 10}
        failed = {"kind": "response", "callId": "call-1", "timestampMs": 20, "data": {"status": 500}}
        success = {"kind": "response", "callId": "call-1", "timestampMs": 30, "data": {"status": 200}}
        self.assertEqual(0, audit.completed_at([request, failed], request, 200))
        self.assertEqual(30, audit.completed_at([request, failed, success], request, 200))

    def test_message_marker_matches_plaintext_and_time(self):
        event = {"operation": "inbound_message_decrypted", "timestampMs": 20, "data": {"body": "E2EE-R3-B-002"}}
        self.assertIsNone(audit.message([event], "inbound_message_decrypted", "E2EE-R3-B-002", after=20))
        self.assertEqual(event, audit.message([event], "inbound_message_decrypted", "E2EE-R3-B-002", after=10))

    def test_network_requires_offline_then_recovery(self):
        states = [{"role": "linked", "kind": "default_network", "timestampMs": index,
                   "network": value} for index, value in enumerate(("106", "null", "107"), 1)]
        self.assertTrue(audit.saw_offline_recovery(states))
        self.assertFalse(audit.saw_offline_recovery(states[:2]))


if __name__ == "__main__":
    unittest.main()
